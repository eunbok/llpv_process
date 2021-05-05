package llpvSpark.util;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.text.SimpleDateFormat;
import java.util.Properties;
import javax.net.ssl.SSLContext;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.ssl.SSLContexts;
import org.apache.log4j.Logger;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder.HttpClientConfigCallback;

public class ElasticUt {
  private static final Logger log = Logger.getLogger(ElasticUt.class);
  private RestClient restClient;
  SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
  private static ElasticUt elasticUt = null;

  private ElasticUt() throws Exception {
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
    credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(
        dbProperties.getProperty("elastic.id"), dbProperties.getProperty("elastic.pw")));
    String hosts[] = dbProperties.getProperty("hosts").split(",");
    int elasticPort = Integer.parseInt(dbProperties.getProperty("elastic.port"));
    String elasticProto = dbProperties.getProperty("elastic.proto");
    HttpHost[] httphosts = new HttpHost[hosts.length];
    for (int i = 0; i < hosts.length; i++) {
      httphosts[i] = new HttpHost(hosts[i], elasticPort, elasticProto);
    }

    restClient =
        RestClient.builder(httphosts).setHttpClientConfigCallback(new HttpClientConfigCallback() {
          @Override
          public HttpAsyncClientBuilder customizeHttpClient(
              HttpAsyncClientBuilder httpClientBuilder) {
            return httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider)
                .setSSLContext(sslContext).setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE);
          }
        }).build();
  }

  public static ElasticUt getInstance() throws Exception {
    if (elasticUt == null) {
      synchronized (ElasticUt.class) {
        if (elasticUt == null) {
          elasticUt = new ElasticUt();
        }
      }
    }
    return elasticUt;
  }
  
  public RestClient getConnection() {
    return restClient;
  }
}
