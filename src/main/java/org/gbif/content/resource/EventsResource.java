package org.gbif.content.resource;

import org.gbif.content.config.ContentWsConfigurationProperties;
import org.gbif.content.utils.ConversionUtil;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import biweekly.Biweekly;
import biweekly.ICalendar;
import com.codahale.metrics.annotation.Timed;
import com.sun.syndication.feed.synd.SyndEntry;
import com.sun.syndication.feed.synd.SyndFeed;
import com.sun.syndication.feed.synd.SyndFeedImpl;
import com.sun.syndication.io.FeedException;
import com.sun.syndication.io.SyndFeedOutput;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.util.LocaleUtils;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.sort.SortOrder;
import org.gbif.content.utils.WebApplicationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Resource class that provides RSS and iCal feeds for events and news.
 */
@RequestMapping(value = "newsroom", produces = MediaType.APPLICATION_JSON_VALUE)
@RestController
public class EventsResource {

  private static final Logger LOG = LoggerFactory.getLogger(EventsResource.class);

  private static final String START_FIELD = "start";

  private static final String CREATED_AT_FIELD = "createdAt";

  private static final String GBIF_REGION_FIELD = "gbifRegion";

  private static final Pattern HYPHEN = Pattern.compile("-");

  /**
   * Default page size for ElasticSearch
   */
  private static final int DEFAULT_SIZE = 10;

  private static final QueryBuilder UPCOMING_EVENTS =
      QueryBuilders
          .rangeQuery(START_FIELD)
          .gte("now/d")
          .includeUpper(Boolean.FALSE);

  private static final QueryBuilder SEARCHABLE = QueryBuilders.termQuery("searchable", Boolean.TRUE);

  private static final String MEDIA_TYPE_ICAL = "text/iCal";

  private final Client esClient;

  private final ContentWsConfigurationProperties configuration;

  /**
   * Creates a new Rss Feed using the common GBIF content.
   */
  private static SyndFeed newEventsFeed() {
    SyndFeed feed = new SyndFeedImpl();
    feed.setFeedType("rss_2.0");
    feed.setTitle("Upcoming events");
    feed.setDescription("GBIF Upcoming News");
    feed.setLanguage("en");
    feed.setLink("http://www.gbif.org/newsroom/events/upcoming.xml");
    return feed;
  }

  /**
   * Creates a new Rss Feed using the common GBIF content.
   */
  private static SyndFeed newNewsFeed() {
    SyndFeed feed = new SyndFeedImpl();
    feed.setFeedType("rss_2.0");
    feed.setTitle("GBIF news feed");
    feed.setDescription("GBIF News");
    feed.setLanguage("en");
    feed.setLink("http://www.gbif.org/newsroom/news/rss");
    return feed;
  }

  /**
   * Full constructor.
   *
   * @param esClient      ElasticSearch client
   * @param configuration configuration settings
   */
  public EventsResource(Client esClient, ContentWsConfigurationProperties configuration) {
    this.esClient = esClient;
    this.configuration = configuration;
  }

  // TODO: 06/07/2020 produces ical only (or both ical and json)
  // TODO: 06/07/2020 path with extension
  // TODO: 06/07/2020 Timed
  // TODO: 06/07/2020 application/atom+xml

  /**
   * Upcoming events in iCal format.
   */
  @Timed
  @GetMapping(
      path = "events/calendar/upcoming.ics",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MEDIA_TYPE_ICAL)
  public String getUpcomingEventsICal() {
    ICalendar iCal = new ICalendar();
    executeQuery(UPCOMING_EVENTS, START_FIELD, configuration.getEsEventsIndex())
        .getHits().forEach(searchHit -> iCal.addEvent(ConversionUtil.toVEvent(searchHit,
        configuration.getDefaultLocale())));
    return Biweekly.write(iCal).go();
  }

  /**
   * Upcoming events RSS feed.
   */
  @Timed
  @GetMapping(
      path = "events/upcoming.xml",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_ATOM_XML_VALUE)
  public String getUpComingEvents() {
    return toXmlAtomFeed(newEventsFeed(), UPCOMING_EVENTS, START_FIELD, configuration.getEsEventsIndex());
  }

  /**
   * Single event RSS feed in Atom format.
   */
  @Timed
  @GetMapping(
      path = "events/{eventId}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MEDIA_TYPE_ICAL)
  public String getEvent(@PathVariable("eventId") String eventId) {
    ICalendar iCal = new ICalendar();
    iCal.addEvent(ConversionUtil
        .toVEvent(esClient.prepareGet(configuration.getEsEventsIndex(), "content", eventId).get(),
            configuration.getDefaultLocale()));
    return Biweekly.write(iCal).go();
  }

  /**
   * News RSS feeds.
   */
  @Timed
  @GetMapping(
      path = "news/rss",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_ATOM_XML_VALUE)
  public String getNews() {
    return toXmlAtomFeed(newNewsFeed(), null, CREATED_AT_FIELD, configuration.getEsNewsIndex());
  }

  /**
   * New RSS feed for GBIF region.
   */
  @Timed
  @GetMapping(
      path = "news/rss/{gbifRegion}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_ATOM_XML_VALUE)
  public String getNewsByRegion(@PathVariable("gbifRegion") String region) {
    return toXmlAtomFeed(newNewsFeed(), QueryBuilders.termQuery(GBIF_REGION_FIELD, region), CREATED_AT_FIELD, configuration.getEsNewsIndex());
  }

  /**
   * News RSS feed for a program and language.
   */
  @Timed
  @GetMapping(
      path = "news/rss/{acronym}/{language}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_ATOM_XML_VALUE)
  public String getProgramNews(@PathVariable("acronym") String acronym, @PathVariable("language") String language) {
    return toXmlAtomFeed(newNewsFeed(), programmeQuery(acronym), CREATED_AT_FIELD, configuration.getEsNewsIndex(), getLocale(language));
  }

  /**
   * JSON News for a program and language.
   */
  @Timed
  @GetMapping(
      path = "news/json/{acronym}/{language}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public List<SyndEntry> getProgrammeNewsJson(@PathVariable("acronym") String acronym,
                                              @PathVariable("language") String language) {
    return Arrays.stream(executeQuery(programmeQuery(acronym),
        CREATED_AT_FIELD, configuration.getEsNewsIndex()).getHits().hits())
        .map(searchHit -> ConversionUtil.toFeedEntry(searchHit, getLocale(language),
            configuration.getGbifPortalUrl() + configuration.getEsNewsIndex()))
        .collect(Collectors.toList());
  }

  /**
   * Data uses RSS feed.
   */
  @Timed
  @GetMapping(
      path = "uses/rss",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_ATOM_XML_VALUE)
  public String getDataUses() {
    return toXmlAtomFeed(newNewsFeed(), null, CREATED_AT_FIELD, configuration.getEsDataUseIndex());
  }

  /**
   * Parse language into a Locale.
   */
  private String getLocale(String language) {
    try {
      Optional<String> optLanguage = Optional.ofNullable(language);
      optLanguage.ifPresent(languageLocale -> LocaleUtils.parse(HYPHEN.matcher(language).replaceAll("_")));
      return optLanguage.orElse(configuration.getDefaultLocale());
    } catch (IllegalArgumentException ex) {
      LOG.error("Error generating locale", ex);
      throw new WebApplicationException(String.format("Language %s is not supported", language),
          HttpStatus.BAD_REQUEST);
    }
  }

  /**
   * Builds a programme:id query builder.
   */
  private QueryBuilder programmeQuery(String acronym) {
    return QueryBuilders.termQuery("programmeTag", findProgrammeId(acronym));
  }

  /**
   * Finds the programme id by its acronym.
   */
  private String findProgrammeId(String acronym) {
    SearchResponse response = executeQuery(QueryBuilders.termQuery("acronym", acronym),
        CREATED_AT_FIELD, configuration.getEsProgrammeIndex());
    return Arrays.stream(response.getHits().getHits())
        .map(SearchHit::getId).findFirst()
        .orElseThrow(() -> new WebApplicationException(
                String.format("Project acronym %s not found", acronym),
                HttpStatus.BAD_REQUEST));
  }

  /**
   * Executes a query and translates the results into XML Atom Feeds.
   */
  private String toXmlAtomFeed(SyndFeed feed, QueryBuilder filter, String dateSortField, String idxName) {
    return toXmlAtomFeed(feed, filter, dateSortField, idxName, configuration.getDefaultLocale());
  }

  /**
   * Executes a query and translates the results into XML Atom Feeds.
   */
  private String toXmlAtomFeed(SyndFeed feed, QueryBuilder filter, String dateSortField, String idxName,
                               String locale) {
    try {
      feed.setEntries(Arrays.stream(executeQuery(filter, dateSortField, idxName).getHits().hits())
          .map(searchHit -> ConversionUtil.toFeedEntry(searchHit, locale,
              configuration.getGbifPortalUrl() + idxName))
          .collect(Collectors.toList()));
      StringWriter writer = new StringWriter();
      new SyndFeedOutput().output(feed, writer);
      return writer.toString();
    } catch (IOException | FeedException ex) {
      LOG.error("Error generating events RSS feed", ex);

      // TODO: 06/07/2020 WebApplicationException(Status.ERROR) ???
      throw new WebApplicationException("Error generating events RSS feed", HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  /**
   * Executes the default search query.
   */
  private SearchResponse executeQuery(String query, QueryBuilder filter, String dateSortField,
                                      String idxName) {
    BoolQueryBuilder queryBuilder = QueryBuilders.boolQuery()
        .filter(SEARCHABLE)
        .must(query == null ?
            QueryBuilders.matchAllQuery() :
            QueryBuilders.wrapperQuery(query));
    Optional.ofNullable(filter)
        .ifPresent(queryBuilder::filter);

    return esClient
        .prepareSearch(idxName)
        .setQuery(queryBuilder)
        .addSort(dateSortField, SortOrder.DESC)
        .setSize(DEFAULT_SIZE)
        .execute()
        .actionGet();
  }

  /**
   * Executes the default search query.
   */
  private SearchResponse executeQuery(QueryBuilder filter, String dateSortField, String idxName) {
    return executeQuery(null, filter, dateSortField, idxName);
  }
}
