package eu.europeana.sitemap.service;


import com.mongodb.BasicDBObject;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import eu.europeana.domain.StorageObject;
import eu.europeana.features.ObjectStorageClient;
import eu.europeana.sitemap.Naming;
import eu.europeana.sitemap.exceptions.SiteMapConfigException;
import eu.europeana.sitemap.exceptions.SiteMapException;
import eu.europeana.sitemap.exceptions.UpdateAlreadyInProgressException;
import eu.europeana.sitemap.mongo.MongoProvider;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang.time.DateFormatUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jclouds.io.payloads.ByteArrayPayload;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/**
 * Created by ymamakis on 11/16/15.
 */
@Service
@Primary
public class GenerateSitemapServiceImpl implements GenerateSitemapService {


    private static final Logger LOG = LogManager.getLogger(GenerateSitemapServiceImpl.class);

    /** XML definitions **/
    private static final String XML_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
    private static final String SITEMAP_HEADER =
            "<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">";
    private static final String URLSET_HEADER =
            "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\""+
                    " xmlns:image=\"http://www.google.com/schemas/sitemap-image/1.1\""+
                    " xmlns:geo=\"http://www.google.com/geo/schemas/sitemap/1.0\">";
    private static final String URL_OPENING = "<url>";
    private static final String URL_CLOSING = "</url>";
    private static final String LOC_OPENING = "<loc>";
    private static final String LOC_CLOSING = "</loc>";
    private static final char LN = '\n';
    private static final String HTML = ".html";
    private static final String FROM = "?from=";
    private static final String TO = "&to=";
    private static final String SITEMAP_OPENING = "<sitemap>";
    private static final String SITEMAP_CLOSING = "</sitemap>";
    private static final String SITEMAP_HEADER_CLOSING = "</sitemapindex>";
    private static final String URLSET_HEADER_CLOSING = "</urlset>";
    private static final String PRIORITY_OPENING = "<priority>";
    private static final String PRIORITY_CLOSING = "</priority>";
    private static final String LASTMOD_OPENING = "<lastmod>";
    private static final String LASTMOD_CLOSING = "</lastmod>";

    /** Used mongo fields **/
    private static final String ABOUT = "about";
    private static final String LASTUPDATED = "timestampUpdated";
    private static final String COMPLETENESS = "europeanaCompleteness";

    private static final String UPDATE_IN_PROGRESS = "In progress";
    private static final String UPDATE_FINISHED = "Finished";

    public static final int NUMBER_OF_ELEMENTS = 45_000;

    private final MongoProvider mongoProvider;
    private final ObjectStorageClient objectStorageProvider;
    private final ActiveSiteMapService activeSiteMapService;
    private final ReadSitemapService readSitemapService;
    private final ResubmitService resubmitService;

    @Value("${portal.base.url}")
    private String portalBaseUrl;
    @Value("${portal.record.urlpath}")
    private String portalRecordUrlPath;
    @Value("${min.record.completeness:0}")
    private int minRecordCompleteness;

    private String status = "initial";
    private Date updateStartTime;

    public GenerateSitemapServiceImpl(MongoProvider mongoProvider, ObjectStorageClient objectStorageProvider,
                                      ActiveSiteMapService activeSiteMapService, ReadSitemapService readSitemapService,
                                      ResubmitService resubmitService) {
        this.mongoProvider = mongoProvider;
        this.objectStorageProvider = objectStorageProvider;
        this.activeSiteMapService = activeSiteMapService;
        this.readSitemapService = readSitemapService;
        this.resubmitService = resubmitService;
    }

    @PostConstruct
    private void init() throws SiteMapConfigException {
        // check configuration for required properties
        if (StringUtils.isEmpty(portalBaseUrl)) {
            throw new SiteMapConfigException("Portal.base.url is not set");
        }
        // to avoid problems with accidental trailing spaces
        portalBaseUrl = portalBaseUrl.trim();

        if (StringUtils.isEmpty(portalRecordUrlPath)) {
            throw new SiteMapConfigException("Portal.record.urlpath is not set");
        }
        portalRecordUrlPath = portalRecordUrlPath.trim();
    }

    /**
     * Generate a new sitemap
     */
    public void generate() {
        DBCollection col = mongoProvider.getCollection();

        DBObject query = new BasicDBObject();
        // 2017-05-30 as part of ticket #624 we are filtering records based on completeness value.
        // This is an experiment to see if high-quality records improve the number of indexed records
        if (minRecordCompleteness >= 0) {
            LOG.info("Filtering records based on Europeana Completeness score of at least {}", minRecordCompleteness);
            query.put(COMPLETENESS, new BasicDBObject("$gte", minRecordCompleteness));
        }

        DBObject fields = new BasicDBObject();
        fields.put(ABOUT, 1);
        fields.put(COMPLETENESS, 1);
        fields.put(LASTUPDATED, 1);

        LOG.info("Starting record query...");
        DBCursor cur = col.find(query, fields).batchSize(NUMBER_OF_ELEMENTS);
        LOG.info("Query finished. Retrieving records...");

        long from = 0;
        long nrRecords = 0;
        int nrSitemaps = 0;

        // create sitemap index file header
        StringBuilder master = new StringBuilder();
        master.append(XML_HEADER).append(LN);
        master.append(SITEMAP_HEADER).append(LN);

        // create sitemap file header
        long fileStartTime = System.currentTimeMillis();
        StringBuilder slave = initializeSlaveGeneration();

        while (cur.hasNext()) {

            DBObject obj = cur.next();
            String about = obj.get(ABOUT).toString();
            int completeness = Integer.parseInt(obj.get(COMPLETENESS).toString());
            Object timestampUpdated = obj.get(LASTUPDATED);
            // very old records do not have a timestampUpdated or timestampCreated field
            Date dateUpdated = (timestampUpdated == null ? null : (Date) timestampUpdated);

            slave.append(URL_OPENING).append(LN)
                    .append(LOC_OPENING).append(portalBaseUrl).append(portalRecordUrlPath).append(about).append(HTML).append(LOC_CLOSING).append(LN)
                    .append(PRIORITY_OPENING).append(completeness > 9 ? "1.0" : ("0." + completeness)).append(PRIORITY_CLOSING).append(LN)
                    .append(generateLastModified(dateUpdated).toString())
                    .append(URL_CLOSING).append(LN);
            nrRecords++;

            if (nrRecords > 0 && (nrRecords % NUMBER_OF_ELEMENTS == 0 || !cur.hasNext())) {
                String fromToText = FROM + from + TO + nrRecords;

                // add fileName to index
                String indexEntry = Naming.SITEMAP_FILE + fromToText;
                master.append(SITEMAP_OPENING).append(LN)
                        .append(LOC_OPENING).append(StringEscapeUtils.escapeXml(portalBaseUrl +"/" + indexEntry)).append(LOC_CLOSING).append(LN)
                        // TODO if we can compare a sitemap file with the previous version, we can check if it has changed and include a lastmodified?
                        //.append(generateLastModified(new Date()).toString())
                        .append(SITEMAP_CLOSING).append(LN);

                // write sitemap file
                slave.append(URLSET_HEADER_CLOSING);
                String fileName = activeSiteMapService.getInactiveFile() + fromToText;
                saveToStorage(fileName, slave.toString());

                long now = System.currentTimeMillis();
                LOG.info("Created sitemap file {} in {} ms", fileName, (now-fileStartTime));
                fileStartTime = now;

                // prepare for next sitemap file
                slave = initializeSlaveGeneration();
                from = nrRecords;
                nrSitemaps++;
            }
        }
        cur.close();
        master.append(SITEMAP_HEADER_CLOSING);
        saveToStorage(Naming.SITEMAP_INDEX_FILE, master.toString());
        LOG.info("Records processed {}, written {} sitemap files and 1 sitemap index file", nrRecords, nrSitemaps);
    }

    private StringBuilder initializeSlaveGeneration() {
        return new StringBuilder().append(XML_HEADER).append(LN).append(URLSET_HEADER).append(LN);
    }

    private StringBuilder generateLastModified(Date lastModifiedDate) {
        StringBuilder result = new StringBuilder();
        if (lastModifiedDate != null) {
            result.append(LASTMOD_OPENING);
            result.append(DateFormatUtils.format(lastModifiedDate, DateFormatUtils.ISO_DATE_FORMAT.getPattern()));
            result.append(LASTMOD_CLOSING);
            result.append(LN);
        }
        return result;
    }


    private void saveToStorage(String key, String value) {
        try {
            ByteArrayPayload payload = new ByteArrayPayload(value.getBytes(StandardCharsets.UTF_8));
            String eTag = objectStorageProvider.put(key, payload);
            //Verify Data
            int nSaveAttempts = 1;
            boolean siteMapCacheFileExists = checkIfFileExists(key);
            if (StringUtils.isEmpty(eTag) || !siteMapCacheFileExists) {
                    int maxAttempts = 3;
                    while (nSaveAttempts < maxAttempts && (StringUtils.isEmpty(eTag) || !siteMapCacheFileExists)) {
                        LOG.info("Failed to save to storage provider (filename={}, siteMapCacheFileExists={})", key, siteMapCacheFileExists);
                        long timeout = nSaveAttempts * 5000L;
                        LOG.info("Waiting {} seconds to try again", (timeout / 1000) );
                        Thread.sleep(timeout);
                        LOG.info("Retrying to save the file");
                        eTag = objectStorageProvider.put(key, payload);
                        siteMapCacheFileExists = checkIfFileExists(key);
                        nSaveAttempts++;
                    }
            }
        } catch (InterruptedException e) {
            LOG.warn("Saving to storage was interrupted", e);
            Thread.currentThread().interrupt();
        }
    }

    private boolean checkIfFileExists(String id) {
        return objectStorageProvider.isAvailable(id);
    }

    public MongoProvider getMongoProvider() {
        return mongoProvider;
    }

    public ObjectStorageClient getObjectStorageProvider() {
        return objectStorageProvider;
    }

    /**
     * Delete the old sitemap at the currently inactive blue/green instance
     */
    private void delete() {
        List<StorageObject> list = objectStorageProvider.list();
        if(list.isEmpty()){
            LOG.info("No files to remove.");
        }

        int i = 0;
        String inactiveFilename = activeSiteMapService.getInactiveFile();
        LOG.info("Deleting all old files with the name {}", inactiveFilename);
        for (StorageObject obj : list) {
            if (obj.getName().contains(inactiveFilename)) {
                objectStorageProvider.delete(obj.getName());
                i++;
            }
            // report on progress
            if (i > 0 && i % 100 == 0) {
                LOG.info("Removed {} old files", i);
            }
        }
        LOG.info("Removed all {} old files", i);
    }

    /**
     * checks if we can start the update, or if an update is already in progress
     * @throws UpdateAlreadyInProgressException
     */
    private void setUpdateInProgress() throws UpdateAlreadyInProgressException {
        // TODO instead of locking based on the status variable, it would be much better to lock based on a file placed in the storage provider.
        // This way we prevent multiple instances simultaneously updating records. We do however need a good mechanism to
        // clean any remaining lock from failed applications.
        synchronized(this) {
            if (UPDATE_IN_PROGRESS.equalsIgnoreCase(status)) {
                String msg = "There is already an update in progress (started at " + updateStartTime + ")";
                LOG.warn(msg);
                throw new UpdateAlreadyInProgressException(msg);
            } else {
                status = UPDATE_IN_PROGRESS;
                updateStartTime = new Date();
                LOG.info("Starting update process...");
            }
        }
    }

    private void setUpdateDone() {
        synchronized(this) {
            updateStartTime = null;
            status = UPDATE_FINISHED;
            LOG.info("Status: {}", status);
        }
    }

    /**
     * @see GenerateSitemapService#update()
     */
    @Override
    public void update() throws SiteMapException {
        setUpdateInProgress();
        try {
            // First clear all old records from the inactive file
            delete();

            // Temporary save the contents of the index file
            String oldIndex = readSitemapService.getIndexFileContent();

            // Then write records to the inactive file
            long startTime = System.currentTimeMillis();
            generate();
            LOG.info("Sitemap generation completed in {} seconds", (System.currentTimeMillis() - startTime) / 1000);

            //Switch to updated cached file
            String activeFile = activeSiteMapService.switchFile();
            LOG.info("Switched active sitemap to {}", activeFile);

            // Notify search engines, but only if index file has changed
            String newIndex = readSitemapService.getIndexFileContent();
            if (newIndex.equalsIgnoreCase(oldIndex)) {
                LOG.info("Index has not changed");
            } else {
                LOG.info("Index has changed");
                resubmitService.notifySearchEngines();
            }
        } catch (Exception e) {
            LOG.error("Error updating sitemap {}", e.getMessage(), e);
         //   sendUpdateFailedEmail(e);
            throw new SiteMapException("Error updating sitemap", e);
        } finally {
            setUpdateDone();
        }
    }

    //private void sendUpdateFailedEmail(Exception e) {
//        SimpleMailMessage mailMessage = new SimpleMailMessage();
//        mailMessage.setTo();
   // }

}
