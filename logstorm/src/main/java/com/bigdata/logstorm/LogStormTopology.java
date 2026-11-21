package com.bigdata.logstorm;

import com.bigdata.logstorm.bolt.ErrorAlertBolt;
import com.bigdata.logstorm.bolt.PageCountBolt;
import com.bigdata.logstorm.bolt.ParseBolt;
import com.bigdata.logstorm.bolt.ReportBolt;
import com.bigdata.logstorm.spout.LogSpout;
import org.apache.storm.Config;
import org.apache.storm.StormSubmitter;
import org.apache.storm.topology.TopologyBuilder;
import org.apache.storm.topology.base.BaseWindowedBolt.Duration;
import org.apache.storm.tuple.Fields;

/**
 * Point d'entrée : construit le DAG (la topologie) et le soumet à Nimbus.
 *
 * <pre>
 * log-spout --shuffle--> parse --fields(url)--> page-count (10 s) --global--> report
 *                          \--stream "errors", global--> error-alert
 * </pre>
 *
 * Mode local  : storm local --local-ttl 60 logstorm-1.0.jar com.bigdata.logstorm.LogStormTopology
 * Mode cluster: storm jar logstorm-1.0.jar com.bigdata.logstorm.LogStormTopology logstorm
 */
public class LogStormTopology {

    public static void main(String[] args) throws Exception {
        // Nom de la topologie : 1er argument, sinon "logstorm"
        String name = args.length > 0 ? args[0] : "logstorm";

        // Le TopologyBuilder sert à déclarer les nœuds (spouts/bolts) et les arêtes (groupings)
        TopologyBuilder builder = new TopologyBuilder();

        // SOURCE : 1 executor qui génère les lignes de log
        builder.setSpout("log-spout", new LogSpout(), 1);

        // PARSING : 2 executors (threads) mais 4 tasks (instances).
        // Avoir plus de tasks que d'executors permet de monter jusqu'à 4 executors
        // à chaud avec "storm rebalance ... -e parse=4", sans redéployer.
        // shuffleGrouping : répartition aléatoire équilibrée (pas besoin de clé ici).
        builder.setBolt("parse", new ParseBolt(), 2)
               .setNumTasks(4)
               .shuffleGrouping("log-spout");

        // COMPTAGE : fenêtre tumbling de 10 s (tranches sans chevauchement).
        // fieldsGrouping sur "url" : une même URL arrive TOUJOURS sur la même task,
        // sinon chaque task n'aurait qu'un compte partiel.
        builder.setBolt("page-count",
                        new PageCountBolt().withTumblingWindow(Duration.seconds(10)), 2)
               .fieldsGrouping("parse", new Fields("url"));

        // ALERTE : abonné au stream nommé "errors" (et non au stream par défaut).
        // globalGrouping : tout arrive sur une seule task -> un seul compteur global.
        builder.setBolt("error-alert", new ErrorAlertBolt(), 1)
               .globalGrouping("parse", ParseBolt.ERROR_STREAM);

        // RAPPORT : rassemble les comptes des 2 tasks page-count en un seul classement
        builder.setBolt("report", new ReportBolt(), 1)
               .globalGrouping("page-count");

        Config conf = new Config();
        conf.setNumWorkers(2);           // 2 JVM workers (réparties sur les supervisors)
        conf.setMaxSpoutPending(1000);   // max 1000 tuples "en vol" (non ackés) par task de spout
        conf.setMessageTimeoutSecs(30);  // un tuple non acké en 30 s => fail() puis rejeu.
                                         // Doit être > durée de fenêtre (10 s) pour les bolts fenêtrés.

        // Soumission à Nimbus. Avec "storm local", cet appel est intercepté
        // et la topologie tourne dans un LocalCluster en mémoire (une seule JVM).
        StormSubmitter.submitTopology(name, conf, builder.createTopology());
    }
}
