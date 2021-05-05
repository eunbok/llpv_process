package llpvSpark.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.entity.NStringEntity;
import org.apache.http.util.EntityUtils;
import org.apache.log4j.Logger;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.json.JSONArray;
import org.json.JSONObject;

public class ElasticDao {
	private static final Logger log = Logger.getLogger(ElasticDao.class);
	private RestClient restClient;
	
	SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

	public ElasticDao() throws Exception {
	  restClient = ElasticUt.getInstance().getConnection();
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
							&& !"�̺з�".equals(jarr.getJSONObject((int) slot.get(j)).getString("medium_category"))) {
						continue;
					}

					jarr.getJSONObject((int) slot.get(j)).put("large_category", large_category);
					jarr.getJSONObject((int) slot.get(j)).put("medium_category", medium_category);
				}
			}
		}

	}
}
