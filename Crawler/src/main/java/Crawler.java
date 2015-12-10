import com.google.common.base.Joiner;
import com.google.common.collect.Sets;
import com.google.common.io.CharStreams;
import com.mashape.unirest.http.Headers;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import lombok.Builder;
import lombok.extern.java.Log;
import org.javatuples.Pair;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * @author blueden
 */
@Log
public class Crawler implements ICrawler {
  private ExecutorService executor ;
  List roots;
  List exclude;
  boolean strict;
  int max_redirect;
  int max_tasks;
  int max_tries;
  Queue<Pair> q;
  Set seen_urls;
  PriorityQueue done;
  Set root_domains;
  Long t0, t1;

  Crawler(List<String> roots, List<String> exclude, boolean strict, int max_redirect,
           int max_tasks, int max_tries) {
    this.roots = roots;
    this.exclude = exclude;
    this.strict = strict;
    this.max_redirect = max_redirect;
    this.max_tries = max_tries;
    this.max_tasks = max_tasks;
    this.q = new PriorityQueue<>();
    this.seen_urls = new HashSet<>();
    this.done = new PriorityQueue<>();
    this.root_domains = new HashSet<>();
    executor = (ExecutorService) Executors.newCachedThreadPool();
    //root_domains 收集了各种 root
    roots.parallelStream().forEach(
        root -> {
          URL url = null;
          try {
            url = new URL(root);
          } catch (MalformedURLException e) {
            log.warning("解析 URL 出错");
          }
          checkNotNull(url);
          String host = url.getHost();
          int port = url.getPort();
          //todo
          if (Pattern.matches("\\A[\\d\\.]*\\Z", host)) {
            this.root_domains.add(host);
          } else {
            host = host.toLowerCase();
            if (this.strict) {
              this.root_domains.add(host);
            } else {
              this.root_domains.add(lenient_host(host));
            }
          }
        }
    );

    //遍历roots执行add_url
    for (String root : roots) {
      this.add_url(root, max_redirect);
    }

    t0 = System.currentTimeMillis();
    t1 = null;
  }

  @Override
  public String lenient_host(String host) {
    String[] arr = host.split("\\.");
    return Joiner.on("").join(Arrays.copyOfRange(arr, arr.length - 2, arr.length));
  }

  @Override
  public boolean is_redirect(HttpResponse httpResponse) {
    return Arrays.asList(new int[]{300, 301, 302, 303, 307}).contains(httpResponse.getStatus());
  }

  @Override
  public boolean host_okay(String host) {
    /*
    Check if a host should be crawled.
    A literal match (after lowercasing) is always good.  For hosts
    that don't look like IP addresses, some approximate matches
    are okay depending on the strict flag.
    */
    host = host.toLowerCase();
    if (root_domains.contains(host)) {
      return true;
    }
    if (Pattern.matches("\\A[\\d\\.]*\\Z", host)) {
      return false;
    }
    if (this.strict) {
      return this._host_okay_strictish(host);
    } else {
      return this._host_okay_lenient(host);
    }
  }

  @Override
  public boolean _host_okay_strictish(String host) {
    if (host.startsWith("www.")) {
      host = host.substring(4, host.length());
    } else {
      host = "www." + host;
    }
    return root_domains.contains(host);
  }

  @Override
  public boolean _host_okay_lenient(String host) {
    return root_domains.contains(lenient_host(host));
  }

  @Override
  public void record_statistic(FetchStatistic fetch_statistic) {
    this.done.add(fetch_statistic);
  }

  @Override
  public Pair<FetchStatistic, Set> parse_links(HttpResponse response, String url) {
    Set<String> links = new HashSet<>();

    String mimeType = null;
    String encoding = null;
    String body = "";

    Headers headers = response.getHeaders();

    if (response.getStatus() == 200) {
      String[] contentType = headers.getFirst("content-type").split(";");

      mimeType = contentType[0];
      encoding = contentType[1];
      if (mimeType.matches("text/html|application/xml")) {
        try {
          body = CharStreams.toString(new InputStreamReader(response.getRawBody(), "utf-8"));
        } catch (IOException e) {
          log.warning(e.getMessage());
        }

        // The HTML page as a String
        Pattern linkPattern = Pattern.compile("href=[\"']([^\\s\"'<>]+)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher pageMatcher = linkPattern.matcher(body);

        while (pageMatcher.find()) {
          links.add(pageMatcher.group(1));
        }

        if (links != null) {
          log.info(String.format("got %d distinct urls from %s", links.size(), url));//todo
        }

        //去掉fragment
        links.forEach(link -> {
          try {
            URL normalized = new URL(new URL(url), link);//todo
            String defragmented = normalized.getPath().split("#")[0];
            if (url_allowed(defragmented)) {
              links.add(defragmented);
            }
          } catch (Exception e) {
            log.warning(e.getMessage());
          }
        });
      }
    }

    FetchStatistic stat = FetchStatistic.builder().url(url).status(response.getStatus())
        .size(body.length()).content_type(mimeType).encoding(encoding)
        .num_urls(links.size()).num_new_urls(links.size() - seen_urls.size()).build();
    return new Pair<>(stat, links);
  }

  @Override
  public void fetch(String url, int max_redirect) {
    int tries = 0;
    String exception = null;
    HttpResponse<InputStream> response = null;
    while (tries < this.max_tries) {
      try {
        response = Unirest.get(url).asBinary();

        if (tries > 1) {
          log.warning(String.format("try %s for %s success", tries, url));
        }
        break;
      } catch (UnirestException e) {
        log.warning(e.getMessage());
        exception = e.getMessage();
      }
      tries += 1;
    }

    if (tries >= this.max_tries) {
      log.warning(String.format("%s failed after %s tries", url, max_tries));
      record_statistic(FetchStatistic.builder().url(url).exception(exception).build());
      return;
    }

    try {
      if (is_redirect(response)) {

        Headers headers = response.getHeaders();
        String location = headers.getFirst("location");
        String next_url = url;//todo
        this.record_statistic(FetchStatistic.builder().url(url).nextUrl(next_url).status(response.getStatus()).build());
        if (seen_urls.contains(next_url)) {
          return;
        }
        if (max_redirect > 0) {
          log.info(String.format("redirect to %s from %s", next_url, url));
          add_url(next_url, max_redirect - 1);
        } else {
          log.warning(String.format("redirect limit reached for %s from %s ", next_url, url));
        }
      } else {
        Pair<FetchStatistic, Set> pair = parse_links(response, url);
        record_statistic(pair.getValue0());
        Set restUrls = Sets.difference(pair.getValue1(), seen_urls);
        restUrls.forEach(restUrl -> {
          q.add(new Pair<>(restUrl, max_redirect));
        });
        seen_urls.addAll(pair.getValue1());
      }
    } finally {
      // return response.release();//todo
    }
  }

  class CrawlTask implements Runnable {
    public void run() {
      try {
        while (q.peek() != null) {
          Pair<String, Integer> pair = q.poll();
          fetch(pair.getValue0(), pair.getValue1());
        }
      } catch (Exception e) {
        log.info(e.getMessage());
      }
    }
  }

  @Override
  public boolean url_allowed(String url) {
    if (exclude != null && exclude.contains(url)) {
      return false;
    }
    URL parts = null;
    try {
      parts = new URL(url);
    } catch (MalformedURLException e) {
      log.warning("URL 解析出错");
    }
    if (!parts.getProtocol().matches("http|https")) {
      log.warning("URL 协议出错");
      return false;
    }
    if (host_okay(parts.getHost())) {
      log.warning(String.format("skipping non-root host in %s", url));
      return false;
    }
    return true;
  }

  @Override
  public void add_url(String url, int max_redirect) {
    log.info(String.format("adding %s %s", url, max_redirect));
    seen_urls.add(url);
    q.add(new Pair(url, max_redirect));
  }

  @Override
  public void crawl() {
    for (int i = 0; i <= max_tasks; i++) {
      executor.execute(new CrawlTask());
    }
    executor.shutdown();
  }

//  public static void main(String[] args) {
//    List<String> roots = new ArrayList<>();
//    roots.add("http://xkcd.com");
//    List<String> exclude = new ArrayList<>();
//    boolean strict = false;
//    int max_redirect = 10;
//    Set seen_urls = new HashSet<>();
//    Crawler Crawler = new Crawler(roots, exclude, strict, max_redirect, 10, 10, new PriorityQueue<>(), new HashSet<>(), new ArrayList<>(), new HashSet<>());
//    Crawler.crawl();
//    System.out.println(Crawler.q);
//  }
}