package llpvSpark;

import java.io.FileInputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.apache.spark.SparkConf;
import org.apache.spark.TaskContext;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.VoidFunction;
import org.apache.spark.streaming.Duration;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaInputDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.apache.spark.streaming.kafka010.CanCommitOffsets;
import org.apache.spark.streaming.kafka010.ConsumerStrategies;
import org.apache.spark.streaming.kafka010.HasOffsetRanges;
import org.apache.spark.streaming.kafka010.KafkaUtils;
import org.apache.spark.streaming.kafka010.LocationStrategies;
import org.apache.spark.streaming.kafka010.OffsetRange;

import llpvSpark.util.ElasticUt;

public class PrcSave {
	private static final Logger log = Logger.getLogger(PrcSave.class);
	static boolean is_show = true;

	public static void main(String args[]) throws Exception {
		FileInputStream log4jRead = new FileInputStream("log4j.properties");
		Properties log4jProperty = new Properties();
		log4jProperty.load(log4jRead);
		PropertyConfigurator.configure(log4jProperty);

		FileInputStream dbRead = new FileInputStream("db.properties");
		Properties dbProperties = new Properties();
		dbProperties.load(dbRead);

		ElasticUt elasticUt = new ElasticUt();
		SparkConf conf = new SparkConf().setAppName("llpv").setMaster("local[*]");
		Logger.getLogger("org").setLevel(Level.OFF);
		Logger.getLogger("akka").setLevel(Level.OFF);
		JavaStreamingContext ssc = new JavaStreamingContext(conf, new Duration(1000));

		Map<String, Object> kafkaParams = new HashMap<>();

		StringBuilder servers = new StringBuilder();
		String hosts[] = dbProperties.getProperty("hosts").split(",");
		String kafkaPort = dbProperties.getProperty("kafka.port");
		String topicName = dbProperties.getProperty("topic.name");

		for (String host : hosts) {
			if (servers.length() > 0) {
				servers.append(",");
			}
			servers.append(host + ":" + kafkaPort);
		}
		
		kafkaParams.put("bootstrap.servers", servers.toString());
		kafkaParams.put("key.deserializer", StringDeserializer.class);
		kafkaParams.put("value.deserializer", StringDeserializer.class);
		kafkaParams.put("key.serializer", StringDeserializer.class);
		kafkaParams.put("value.serializer", StringDeserializer.class);

		kafkaParams.put("group.id", topicName);
		kafkaParams.put("auto.offset.reset", "latest");
		kafkaParams.put("enable.auto.commit", false);

		Collection<String> topics = Arrays.asList(topicName);

		JavaInputDStream<ConsumerRecord<String, String>> stream = KafkaUtils.createDirectStream(ssc,
				LocationStrategies.PreferConsistent(),
				ConsumerStrategies.<String, String>Subscribe(topics, kafkaParams));

		JavaDStream<String> dStream = stream.map((x) -> x.value());

		dStream.foreachRDD(rdd -> {
			long cnt = rdd.count();
			if (cnt > 0 || is_show) {
				log.debug("rdd cnt : " + rdd.count());
				is_show = true;
			}
			if (cnt > 0)
				elasticUt.saveToES(rdd.collect());
		});

		// @EXP 표 형태의 남아있는 개수
		stream.foreachRDD(new VoidFunction<JavaRDD<ConsumerRecord<String, String>>>() {
			@Override
			public void call(JavaRDD<ConsumerRecord<String, String>> rdd) {
				final OffsetRange[] offsetRanges = ((HasOffsetRanges) rdd.rdd()).offsetRanges();
				if (is_show) {
					log.debug("---------------------------------------");
					log.debug("topic\t|partition\t|from\t|until");
					log.debug("---------------------------------------");
				}
				rdd.foreachPartition(new VoidFunction<Iterator<ConsumerRecord<String, String>>>() {
					@Override
					public void call(Iterator<ConsumerRecord<String, String>> consumerRecords) {
						OffsetRange o = offsetRanges[TaskContext.get().partitionId()];
						if (is_show) {
							log.debug(o.topic() + "\t|" + o.partition() + "\t\t|" + o.fromOffset() + "\t|"
									+ o.untilOffset());
						}
					}
				});
				if (is_show) {
					log.debug("---------------------------------------");
					is_show = false;
				}
			}
		});

		stream.foreachRDD(new VoidFunction<JavaRDD<ConsumerRecord<String, String>>>() {
			@Override
			public void call(JavaRDD<ConsumerRecord<String, String>> rdd) {
				OffsetRange[] offsetRanges = ((HasOffsetRanges) rdd.rdd()).offsetRanges();
				((CanCommitOffsets) stream.inputDStream()).commitAsync(offsetRanges);
//				log.debug("offset is saved");
			}
		});

		ssc.start();
		ssc.awaitTermination(); // Wait for the computation to terminate

	}
}
