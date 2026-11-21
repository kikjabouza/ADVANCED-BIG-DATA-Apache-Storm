package com.bigdata.logstorm.bolt;

import org.apache.storm.Config;
import org.apache.storm.topology.BasicOutputCollector;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseBasicBolt;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.utils.TupleUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Reçoit les comptes (url, count, avgLatency) des tasks page-count et affiche
 * toutes les 10 s le classement des pages, grâce à un TICK TUPLE.
 *
 * Un tick tuple est un tuple système envoyé par Storm à intervalle régulier :
 * il sert d'horloge à l'intérieur d'un bolt.
 */
public class ReportBolt extends BaseBasicBolt {

    private static final Logger LOG = LoggerFactory.getLogger(ReportBolt.class);

    private final Map<String, long[]> latest = new HashMap<>();   // url -> [count, avgLatency]

    /** Configuration propre à ce bolt : un tick tuple toutes les 10 s. */
    @Override
    public Map<String, Object> getComponentConfiguration() {
        Map<String, Object> conf = new HashMap<>();
        conf.put(Config.TOPOLOGY_TICK_TUPLE_FREQ_SECS, 10);
        return conf;
    }

    @Override
    public void execute(Tuple tuple, BasicOutputCollector collector) {
        // 1) Tick tuple : on affiche le classement puis on remet à zéro
        if (TupleUtils.isTick(tuple)) {
            if (latest.isEmpty()) return;
            StringBuilder sb = new StringBuilder("\n[RAPPORT] Requêtes par page (10 dernières secondes)\n");
            latest.entrySet().stream()
                  .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))   // tri décroissant
                  .forEach(e -> sb.append(String.format("  %-14s %5d req   latence moy. %4d ms%n",
                          e.getKey(), e.getValue()[0], e.getValue()[1])));
            LOG.info(sb.toString());
            latest.clear();
            return;
        }

        // 2) Tuple normal : on agrège (une URL peut arriver plusieurs fois si plusieurs
        //    fenêtres se sont fermées depuis le dernier tick)
        latest.merge(tuple.getStringByField("url"),
                new long[]{tuple.getLongByField("count"), tuple.getLongByField("avgLatency")},
                (a, b) -> new long[]{a[0] + b[0], (a[1] + b[1]) / 2});
    }

    /** Bolt terminal : il n'émet rien. */
    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) { }
}
