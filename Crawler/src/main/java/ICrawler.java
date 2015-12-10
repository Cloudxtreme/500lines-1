/**
 * Created by chenwj on 2015/12/6.
 */

import com.mashape.unirest.http.HttpResponse;
import lombok.Builder;
import lombok.Data;
import org.javatuples.Pair;

import java.util.Set;

/**
 * @author blueden
 */
interface ICrawler {
  @Data
  @Builder
  public static class FetchStatistic {
    String url;
    String nextUrl;
    int status;
    String exception;
    int size;
    String content_type;
    String encoding;
    int num_urls;
    int num_new_urls;
  }

  String lenient_host(String host);

  boolean is_redirect(HttpResponse httpResponse);

  boolean host_okay(String host);

  boolean _host_okay_strictish(String host);

  boolean _host_okay_lenient(String host);

  void record_statistic(FetchStatistic fetch_statistic);

  Pair<FetchStatistic,Set> parse_links(HttpResponse response,String url);

  void fetch(String url, int max_redirect);

  boolean url_allowed(String url);

  void add_url(String url, int max_redirect);

  void crawl();
}