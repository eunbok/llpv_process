package llpvSpark.util;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import javax.net.ssl.SSLContext;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.nio.entity.NStringEntity;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.util.EntityUtils;
import org.apache.log4j.Logger;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder.HttpClientConfigCallback;
import org.json.JSONArray;
import org.json.JSONObject;

public class ElasticUt {
	private static final Logger log = Logger.getLogger(ElasticUt.class);
	RestClient restClient;
	SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

	public ElasticUt() throws Exception {
		getInstance();
	}

	private void getInstance() throws Exception {
		FileInputStream dbRead = new FileInputStream("db.properties");
		Properties dbProperties = new Properties();
		dbProperties.load(dbRead);
		
		Path trustStorePath = Paths.get(dbProperties.getProperty("trust.store.path"));
		Path keyStorePath = Paths.get(dbProperties.getProperty("key.store.path"));

		KeyStore trustStore = KeyStore.getInstance(dbProperties.getProperty("key.store"));
		KeyStore keyStore = KeyStore.getInstance(dbProperties.getProperty("key.store"));
		try (InputStream is = Files.newInputStream(trustStorePath)) {
			trustStore.load(is, "".toCharArray());
		}
		try (InputStream is = Files.newInputStream(keyStorePath)) {
			keyStore.load(is, "".toCharArray());
		}
		SSLContextBuilder sslBuilder = SSLContexts.custom().loadTrustMaterial(trustStore, null)
				.loadKeyMaterial(keyStore, "".toCharArray());

		final SSLContext sslContext = sslBuilder.build();

		final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
		credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(dbProperties.getProperty("elastic.id"), dbProperties.getProperty("elastic.pw")));
		String hosts[] = dbProperties.getProperty("hosts").split(",");
		int elasticPort = Integer.parseInt(dbProperties.getProperty("elastic.port"));
		String elasticProto = dbProperties.getProperty("elastic.proto");
		HttpHost[] httphosts = new HttpHost[hosts.length];
		for(int i = 0; i<hosts.length;i++) {
			httphosts[i] = new HttpHost(hosts[i],elasticPort,elasticProto);
		}
		
		restClient = RestClient
				.builder(httphosts)
				.setHttpClientConfigCallback(new HttpClientConfigCallback() {
					@Override
					public HttpAsyncClientBuilder customizeHttpClient(HttpAsyncClientBuilder httpClientBuilder) {
						return httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider)
								.setSSLContext(sslContext).setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE);
					}
				}).build();

	}

	public String saveToES(List<String> data) throws Exception {

		String msg = "";
		StringBuilder str = new StringBuilder();
		JSONArray jarr = new JSONArray();
		for (String log : data) {
			JSONObject jo = new JSONObject(log);
			jo.put("@timestamp", sdf.format(new Date()));
			jo.put("large_category", "미분류");
			jo.put("medium_category", "미분류");
			jarr.put(jo);
		}

		percolate(jarr);

		for (int i = 0; i < jarr.length(); i++) {
			JSONObject jo = jarr.getJSONObject(i);
			str.append("{ \"index\" : { \"_index\" : \"llpv-log\" } }\n" + jo.toString() + "\n");
		}

		Request request = new Request("POST", "/_bulk?filter_path=items.*.error");
		request.setEntity(new NStringEntity(str.toString(), ContentType.APPLICATION_JSON));
		Response response = restClient.performRequest(request);
		String responseBody = EntityUtils.toString(response.getEntity());

		if (!"{}".equals(responseBody))
			log.debug(responseBody);

		return msg;

	}

	private void percolate(JSONArray jarr) throws Exception {
		StringBuilder str = new StringBuilder();
		str.append("{\"query\":{\"percolate\":{ \"field\": \"query\",   \"documents\":" + jarr.toString()
				+ "}},\"sort\":{\"priority\":\"desc\"}}");
		Request request = new Request("POST", "/llpv-percol/_search");
		request.setEntity(new NStringEntity(str.toString(), ContentType.APPLICATION_JSON));
		Response response = restClient.performRequest(request);
		JSONObject responseBody = new JSONObject(EntityUtils.toString(response.getEntity()));
		JSONObject hits = responseBody.getJSONObject("hits");
		if (hits.getJSONObject("total").getInt("value") > 0) {
			JSONArray hitsItem = hits.getJSONArray("hits");
			for (int i = 0; i < hitsItem.length(); i++) {
				JSONObject source = hitsItem.getJSONObject(i).getJSONObject("_source");
				int priority = source.getInt("priority");
				String large_category = source.getString("large_category");
				String medium_category = source.getString("medium_category");
				JSONArray slot = hitsItem.getJSONObject(i).getJSONObject("fields")
						.getJSONArray("_percolator_document_slot");
				for (int j = 0; j < slot.length(); j++) {
					if (priority == 0
							&& !"미분류".equals(jarr.getJSONObject((int) slot.get(j)).getString("medium_category"))) {
						continue;
					}

					jarr.getJSONObject((int) slot.get(j)).put("large_category", large_category);
					jarr.getJSONObject((int) slot.get(j)).put("medium_category", medium_category);
				}
			}
		}

	}
}
