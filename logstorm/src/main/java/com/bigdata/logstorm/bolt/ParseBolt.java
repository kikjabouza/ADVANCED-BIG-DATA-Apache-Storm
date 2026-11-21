package com.bigdata.logstorm.bolt;

import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichBolt;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Découpe une ligne de log en (url, status, latency).
 * Les erreurs 5xx sont AUSSI envoyées sur un second stream nommé "errors".
 *
 * BaseRichBolt : on gère soi-même l'ancrage et l'ack (contrôle total).
 */
public class ParseBolt extends BaseRichBolt {

    /** Nom du second stream, réutilisé dans la topologie pour s'y abonner. */
    public static final String ERROR_STREAM = "errors";

    private static final Logger LOG = LoggerFactory.getLogger(ParseBolt.class);

    // Groupes : 1=IP 2=date 3=méthode 4=url 5=status 6=octets 7=latence
    private static final Pattern LOG_LINE = Pattern.compile(
            "^(\\S+) \\S+ \\S+ \\[([^\\]]+)\\] \"(\\S+) (\\S+) [^\"]*\" (\\d{3}) (\\d+) (\\d+)$");

    private OutputCollector collector;

    @Override
    public void prepare(Map<String, Object> topoConf, TopologyContext context, OutputCollector collector) {
        this.collector = collector;
    }

    @Override
    public void execute(Tuple input) {
        String line = input.getStringByField("line");
        Matcher m = LOG_LINE.matcher(line);
        if (!m.matches()) {
            LOG.warn("Ligne ignorée : {}", line);
            collector.ack(input);       // on ack quand même, sinon elle serait rejouée sans fin
            return;
        }

        String url = m.group(4);
        int q = url.indexOf('?');
        if (q >= 0) url = url.substring(0, q);          // /recherche?q=storm -> /recherche
        int status = Integer.parseInt(m.group(5));
        long latency = Long.parseLong(m.group(7));

        // Émission ANCRÉE (1er argument = tuple d'entrée) : le nouveau tuple devient
        // un enfant de "input" dans l'arbre de tuples suivi par l'acker.
        collector.emit(input, new Values(url, status, latency));

        // Stream nommé "errors" : seul error-alert y est abonné
        if (status >= 500) {
            collector.emit(ERROR_STREAM, input, new Values(url, status, System.currentTimeMillis()));
        }

        // On confirme le traitement du tuple d'entrée
        collector.ack(input);
    }

    /** Ce bolt déclare DEUX streams avec leurs schémas. */
    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declare(new Fields("url", "status", "latency"));                 // stream "default"
        declarer.declareStream(ERROR_STREAM, new Fields("url", "status", "ts"));  // stream "errors"
    }
}
