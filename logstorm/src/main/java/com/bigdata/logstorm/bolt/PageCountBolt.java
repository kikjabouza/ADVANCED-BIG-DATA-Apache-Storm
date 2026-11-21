package com.bigdata.logstorm.bolt;

import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseWindowedBolt;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.apache.storm.windowing.TupleWindow;

import java.util.HashMap;
import java.util.Map;

/**
 * Compte les requêtes par URL et calcule la latence moyenne sur une fenêtre
 * tumbling de 10 s (la taille est réglée dans LogStormTopology avec withTumblingWindow).
 *
 * BaseWindowedBolt : Storm accumule les tuples et appelle execute(TupleWindow)
 * une fois par fenêtre, au lieu d'une fois par tuple.
 */
public class PageCountBolt extends BaseWindowedBolt {

    private OutputCollector collector;

    @Override
    public void prepare(Map<String, Object> topoConf, TopologyContext context, OutputCollector collector) {
        this.collector = collector;
    }

    @Override
    public void execute(TupleWindow window) {
        Map<String, long[]> stats = new HashMap<>();    // url -> [nombre, somme des latences]
        for (Tuple t : window.get()) {                  // tous les tuples des 10 dernières secondes
            long[] s = stats.computeIfAbsent(t.getStringByField("url"), k -> new long[2]);
            s[0]++;
            s[1] += t.getLongByField("latency");
        }
        // Un tuple de sortie par URL : (url, count, avgLatency)
        stats.forEach((url, s) -> collector.emit(new Values(url, s[0], s[1] / s[0])));
        // Pas d'ack manuel : les tuples de la fenêtre sont acquittés automatiquement
        // quand la fenêtre expire.
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declare(new Fields("url", "count", "avgLatency"));
    }
}
