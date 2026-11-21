package com.bigdata.logstorm.spout;

import org.apache.storm.spout.SpoutOutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichSpout;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Values;
import org.apache.storm.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spout = source du flux. Simule des serveurs web : ~200 lignes de log par seconde
 * au format Apache Common Log, suivies de la latence en ms :
 * <pre>192.168.1.7 - - [21/Nov/2026:10:00:00 +0000] "GET /panier HTTP/1.1" 200 1534 87</pre>
 *
 * Émission FIABLE : chaque tuple porte un msgId ; Storm appelle ack() quand tout
 * l'arbre de tuples est traité, ou fail() en cas d'échec/timeout (=> rejeu).
 */
public class LogSpout extends BaseRichSpout {

    private static final Logger LOG = LoggerFactory.getLogger(LogSpout.class);

    private static final String[] PAGES = {"/", "/produits", "/produits/42", "/panier",
            "/paiement", "/compte", "/recherche?q=storm", "/api/stock"};
    private static final String[] METHODS = {"GET", "GET", "GET", "POST"};
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH);

    private static final double NORMAL_ERROR_RATE = 0.02;  // 2 % d'erreurs 5xx en temps normal
    private static final double BURST_ERROR_RATE = 0.35;   // 35 % pendant une rafale
    private static final int BURST_PERIOD_S = 40;          // une rafale toutes les 40 s...
    private static final int BURST_LENGTH_S = 10;          // ...qui dure 10 s

    // Ces champs sont initialisés dans open() et non dans le constructeur :
    // le spout est sérialisé puis envoyé au worker, open() s'exécute sur le worker.
    private SpoutOutputCollector collector;
    private Random random;
    private Map<String, String> pending;   // msgId -> ligne : ce qui n'est pas encore acké
    private long startMs;

    @Override
    public void open(Map<String, Object> conf, TopologyContext context, SpoutOutputCollector collector) {
        this.collector = collector;
        this.random = new Random();
        this.pending = new ConcurrentHashMap<>();
        this.startMs = System.currentTimeMillis();
    }

    /** Appelé en boucle par Storm. Ne jamais bloquer longtemps ici. */
    @Override
    public void nextTuple() {
        Utils.sleep(5);                                 // ~200 lignes/s
        String line = buildLine();
        String msgId = UUID.randomUUID().toString();    // identifiant unique => tuple suivi
        pending.put(msgId, line);                       // on le garde pour pouvoir le rejouer
        collector.emit(new Values(line), msgId);        // emit AVEC msgId = émission fiable
    }

    /** Vrai pendant les 10 dernières secondes de chaque cycle de 40 s. */
    private boolean inErrorBurst() {
        long s = (System.currentTimeMillis() - startMs) / 1000;
        return s % BURST_PERIOD_S >= BURST_PERIOD_S - BURST_LENGTH_S;
    }

    private String buildLine() {
        String ip = "192.168." + random.nextInt(256) + "." + random.nextInt(256);
        String page = PAGES[random.nextInt(PAGES.length)];
        double errorRate = inErrorBurst() ? BURST_ERROR_RATE : NORMAL_ERROR_RATE;
        int status = random.nextDouble() < errorRate
                ? (random.nextBoolean() ? 500 : 503)
                : (random.nextDouble() < 0.08 ? 404 : 200);
        int bytes = 200 + random.nextInt(5000);
        // les erreurs serveur sont aussi plus lentes
        int latency = status >= 500 ? 800 + random.nextInt(2000) : 10 + random.nextInt(200);
        return String.format("%s - - [%s] \"%s %s HTTP/1.1\" %d %d %d", ip,
                ZonedDateTime.now().format(FMT), METHODS[random.nextInt(METHODS.length)],
                page, status, bytes, latency);
    }

    /** Le tuple et tous ses descendants ont été traités : on l'oublie. */
    @Override
    public void ack(Object msgId) {
        pending.remove(msgId);
    }

    /** Échec ou timeout (30 s) : on réémet la même ligne => garantie at-least-once. */
    @Override
    public void fail(Object msgId) {
        String line = pending.get(msgId);
        if (line != null) {
            LOG.warn("Rejeu du message {}", msgId);
            collector.emit(new Values(line), msgId);
        }
    }

    /** Schéma du stream par défaut : un seul champ "line". */
    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declare(new Fields("line"));
    }
}
