import de.tototec.cmdoption.CmdOption;
import de.tototec.cmdoption.CmdlineParser;

import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Created by chenwj on 2015/10/18.
 */
public class Main {
  public static class Config {
    @CmdOption(names = {"--help", "-h"}, description = "Show this help", isHelp = true)
    boolean help;
    @CmdOption(names = {"--iocp"}, description =
        "Use IOCP event loop (Windows only)", isHelp = true)
    boolean iocp;
    @CmdOption(names = {"--select"}, description =
        "Use Select event loop instead of default", isHelp = true)
    boolean select;
    @CmdOption(names = {"--verbose", "-v"}, description =
        "Verbose logging (repeat for more verbose)", isHelp = true)
    boolean verbose;
    @CmdOption(names = {"--quiet", "-q"}, description = "Only log errors", isHelp = true)
    boolean quiet;
    //
    @CmdOption(args = {"url"}, description =
        "Root URL (may be repeated)", minCount = 1, maxCount = -1)
    List<String> roots = new LinkedList<String>();

    @CmdOption(names = {"--exclude"}, args = {"excludeUrl"},description =
        "Exclude matching URLs", minCount = 0, maxCount = -1)
    List<String> excludes = new LinkedList<String>();

    //宽大的,仁慈的
    @CmdOption(names = {"--lenient"}, description = "Lenient host matching", isHelp = true)
    boolean lenient;

    @CmdOption(names = {"--strict"}, description = "Limit concurrent connections", isHelp = true)
    boolean strict;

    @CmdOption(names = {"--max_redirect"}, args = {"max_redirect"}, description =
        "Limit redirection chains (for 301, 302 etc.)")
    int max_redirect;
    @CmdOption(names = {"--max_tries"}, args = {"max_tries"}, description =
        "Limit retries on network errors")
    int max_tries;
    @CmdOption(names = {"--max_tasks"}, args = {"max_tasks"}, description =
        "Limit concurrent connections")
    int max_tasks;
  }

  private static String fixUrl(String url) {
    if (!url.contains("://")) {
      url = "http://" + url;
    }
    return url;
  }

  /**
   * main.
   * @param args should be included in Config List.
   */
  public static void main(String[] args) {
    final Config config = new Config();
    final CmdlineParser cp = new CmdlineParser(config);
    cp.setProgramName("myCrawl");

    // Parse the cmdline, only continue when no errors exist
    cp.parse(args);

    if (config.help) {
      cp.usage();
      System.exit(0);
    }
    System.out.println(config.roots);

    if (config.roots.isEmpty()) {
      System.out.println("Use --help for command line help");
      return;
    }
    config.roots = config.roots.stream().map(Main::fixUrl).collect(Collectors.toList());//too long
    Crawler Crawler = new Crawler(config.roots, config.excludes, config.strict, config.max_redirect, config.max_tasks, config.max_tries);
    Crawler.crawl();
  }
}
