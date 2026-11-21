package com.bigdata.logstorm.bolt;

import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.BasicOutputCollector;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseBasicBolt;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * Lève une ALERTE quand il y a plus de 15 erreurs 5xx sur 10 secondes glissantes
 * (au plus une alerte toutes les 10 s pour ne pas inonder les logs).
 *
 * BaseBasicBolt : Storm ancre et ack automatiquement chaque tuple à la fin de execute().
 */
public class ErrorAlertBolt extends BaseBasicBolt {

    private static final Logger LOG = LoggerFactory.getLogger(ErrorAlertBolt.class);

    private static final int THRESHOLD = 15;            // seuil : > 15 erreurs...
    private static final long WINDOW_MS = 10_000;       // ...sur 10 s glissantes
    private static final long COOLDOWN_MS = 10_000;     // délai minimal entre deux alertes

    private transient Deque<Long> errors;               // horodatages des erreurs récentes
    private long lastAlert;

    @Override
    public void prepare(Map<String, Object> topoConf, TopologyContext context) {
        errors = new ArrayDeque<>();
    }

    @Override
    public void execute(Tuple tuple, BasicOutputCollector collector) {
        long now = System.currentTimeMillis();
        errors.addLast(now);
        // Fenêtre glissante "maison" : on retire les erreurs de plus de 10 s
        while (!errors.isEmpty() && now - errors.peekFirst() > WINDOW_MS) {
            errors.pollFirst();
        }

        if (errors.size() > THRESHOLD && now - lastAlert > COOLDOWN_MS) {
            lastAlert = now;
            String msg = String.format("%d erreurs 5xx en 10 s (dernière sur %s, statut %d)",
                    errors.size(), tuple.getStringByField("url"), tuple.getIntegerByField("status"));
            LOG.warn("[ALERTE] {}", msg);
            collector.emit(new Values(msg, now));   // un bolt aval pourrait envoyer un mail, Slack...
        }
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declare(new Fields("alert", "ts"));
    }
}
