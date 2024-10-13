package fhirspark;

import ca.uhn.fhir.rest.server.exceptions.ResourceGoneException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import fhirspark.adapter.SpecimenAdapter;
import fhirspark.adapter.TherapyRecommendationAdapter;
import fhirspark.resolver.HgncGeneName;
import fhirspark.resolver.OncoKbDrug;
import fhirspark.restmodel.CbioportalRest;
import fhirspark.restmodel.Deletions;
import fhirspark.restmodel.FollowUp;
import fhirspark.restmodel.GeneticAlteration;
import fhirspark.restmodel.Image;
import fhirspark.restmodel.ImageResponse;
import fhirspark.restmodel.Mtb;
import fhirspark.restmodel.PresentationViewModel;
import fhirspark.settings.ConfigurationLoader;
import fhirspark.settings.Settings;
import io.minio.errors.MinioException;
import org.apache.log4j.BasicConfigurator;
import org.eclipse.jetty.http.HttpStatus;
import spark.Request;
import spark.Response;

import javax.ws.rs.core.Cookie;
import java.io.FileInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static spark.Spark.delete;
import static spark.Spark.get;
import static spark.Spark.options;
import static spark.Spark.port;
import static spark.Spark.post;
import static spark.Spark.put;

/**
 * Fhirspark-Application that stores MTB decisions from cBioPortal and is able
 * to transfer those to hospital information systems via HL7 Version 2 and HL7
 * FHIR.
 */
public final class FhirSpark {

    private static JsonFhirMapper jsonFhirMapper;
    private static Settings settings;
    private static Client client = new Client();
    private static ObjectMapper objectMapper = new ObjectMapper(new JsonFactory());

    private FhirSpark() {
    }

    /**
     * @param args args[0] can contain a path to a custom configuration yaml file.
     * @throws Exception Exception if the REST API runs into issues.
     */
    public static void main(final String[] args) throws Exception {
        BasicConfigurator.configure();
        InputStream settingsYaml = ClassLoader.getSystemClassLoader().getResourceAsStream("settings.yaml");
        if (args.length == 1) {
            settingsYaml = new FileInputStream(args[0]);
        }
        ConfigurationLoader configLoader = new ConfigurationLoader();
        settings = configLoader.loadConfiguration(settingsYaml, Settings.class);
        HgncGeneName.initialize(settings.getHgncPath());
        OncoKbDrug.initalize(settings.getOncokbPath());
        SpecimenAdapter.initialize(settings.getSpecimenSystem());
        TherapyRecommendationAdapter.initialize(settings.getObservationSystem(), settings.getStudySystem());
        jsonFhirMapper = new JsonFhirMapper(settings);
        port(settings.getPort());

        options("/mtb/:patientId", (req, res) -> {
            addOptions(req, res);
            res.header("Access-Control-Allow-Methods", "GET, PUT, DELETE");
            return res;
        });

        /**
         *
         * Checks whether the client has permission to view and manipulate the data of the given patientId
         *
         * @param req Incoming Java Spark Request
         * @param patientId requested patientId
         * @return FORBIDDEN_403 if not authorized
         * @return ACCEPTED_202 if authorized
         */
        options("/mtb/:patientId/permission", (req, res) -> {
            addOptions(req, res);
            res.header("Access-Control-Allow-Methods", "GET, PUT, DELETE");
            return res;
        });

        get("/mtb/:patientId/permission", (req, res) -> {
            if (settings.getLoginRequired()
                && (!validateRequest(req) || !validateManipulation(req))) {
                res.status(HttpStatus.FORBIDDEN_403);
                return res;
            }
            addOptions(req, res);
            res.status(HttpStatus.ACCEPTED_202);
            res.header("Cache-Control", "no-cache, no-store, max-age=0");
            return res;
        });

        get("/mtb/:patientId", (req, res) -> {
            if (settings.getLoginRequired() && !validateRequest(req)) {
                res.status(HttpStatus.FORBIDDEN_403);
                return res;
            }
            res.status(HttpStatus.OK_200);
            addContent(req, res);
            res.body(jsonFhirMapper.mtbToJson(req.params(":patientId")));
            return res.body();
        });

        put("/mtb/:patientId", (req, res) -> {
            if (settings.getLoginRequired()
                && (!validateRequest(req) || !validateManipulation(req))) {
                res.status(HttpStatus.FORBIDDEN_403);
                return res;
            }
            res.status(HttpStatus.CREATED_201);
            addContent(req, res);

            List<Mtb> mtbs = objectMapper.readValue(req.body(), CbioportalRest.class).getMtbs();
            jsonFhirMapper.mtbFromJson(req.params(":patientId"), mtbs);
            res.body(req.body());
            return res.body();
        });

        delete("/mtb/:patientId", (req, res) -> {
            if (settings.getLoginRequired()
                && (!validateRequest(req) || !validateManipulation(req))) {
                res.status(HttpStatus.FORBIDDEN_403);
                return res;
            }
            res.status(HttpStatus.OK_200);
            addContent(req, res);
            Deletions deletions = objectMapper.readValue(req.body(), Deletions.class);
            jsonFhirMapper.deleteEntries(req.params(":patientId"), deletions);
            res.body(req.body());
            return res.body();
        });

        options("/mtb/alteration", (req, res) -> {
            addOptions(req, res);
            res.header("Access-Control-Allow-Methods", "POST");
            return res;
        });

        post("/mtb/alteration", (req, res) -> {
            res.status(HttpStatus.OK_200);
            addContent(req, res);
            List<GeneticAlteration> alterations = objectMapper.readValue(req.body(),
                new TypeReference<List<GeneticAlteration>>() {
                });
            res.body(
                objectMapper.writeValueAsString(jsonFhirMapper
                    .getTherapyRecommendationsByAlteration(alterations)));
            return res.body();
        });

        options("/mtb/alteration/pmid", (req, res) -> {
            addOptions(req, res);
            res.header("Access-Control-Allow-Methods", "POST");
            return res;
        });

        post("/mtb/alteration/pmid", (req, res) -> {
            res.status(HttpStatus.OK_200);
            addContent(req, res);
            List<GeneticAlteration> alterations = objectMapper.readValue(req.body(),
                new TypeReference<List<GeneticAlteration>>() {
                });
            res.body(objectMapper.writeValueAsString(jsonFhirMapper.getPmidsByAlteration(alterations)));
            return res.body();
        });

        options("/followup/:patientId", (req, res) -> {
            addOptions(req, res);
            res.header("Access-Control-Allow-Methods", "GET, PUT, DELETE");
            return res;
        });

        get("/followup/:patientId/permission", (req, res) -> {
            if (settings.getLoginRequired()
                && (!validateRequest(req) || !validateManipulation(req))) {
                res.status(HttpStatus.FORBIDDEN_403);
                return res;
            }
            res.status(HttpStatus.ACCEPTED_202);
            res.header("Cache-Control", "no-cache, no-store, max-age=0");
            return res;
        });

        get("/followup/:patientId", (req, res) -> {
            if (settings.getLoginRequired() && !validateRequest(req)) {
                res.status(HttpStatus.FORBIDDEN_403);
                return res;
            }
            res.status(HttpStatus.OK_200);
            addContent(req, res);
            res.body(jsonFhirMapper.followUpToJson(req.params(":patientId")));
            return res.body();
        });

        put("/followup/:patientId", (req, res) -> {
            if (settings.getLoginRequired()
                && (!validateRequest(req) || !validateManipulation(req))) {
                res.status(HttpStatus.FORBIDDEN_403);
                return res;
            }
            res.status(HttpStatus.CREATED_201);
            addContent(req, res);
            List<FollowUp> followUps = objectMapper.readValue(req.body(), CbioportalRest.class).getFollowUps();
            jsonFhirMapper.followUpFromJson(req.params(":patientId"), followUps);
            res.body(req.body());
            return res.body();
        });

        delete("/followup/:patientId", (req, res) -> {
            if (settings.getLoginRequired()
                && (!validateRequest(req) || !validateManipulation(req))) {
                res.status(HttpStatus.FORBIDDEN_403);
                return res;
            }
            res.status(HttpStatus.OK_200);
            addContent(req, res);
            Deletions deletions = objectMapper.readValue(req.body(), Deletions.class);
            jsonFhirMapper.deleteEntries(req.params(":patientId"), deletions);
            res.body(req.body());
            return res.body();
        });

        options("/followup/alteration", (req, res) -> {
            addOptions(req, res);
            res.header("Access-Control-Allow-Methods", "POST");
            return res;
        });

        post("/followup/alteration", (req, res) -> {
            res.status(HttpStatus.OK_200);
            addContent(req, res);
            List<GeneticAlteration> alterations = objectMapper.readValue(req.body(),
                new TypeReference<List<GeneticAlteration>>() {
                });
            res.body(
                objectMapper.writeValueAsString(jsonFhirMapper
                    .getFollowUpsByAlteration(alterations)));
            return res.body();
        });

        options("/presentation/image", (req, res) -> {
            addOptions(req, res);
            res.header("Access-Control-Allow-Methods", "POST");
            return res;
        });

        post("/presentation/:patientId/image", (request, response) -> {
            addContent(request, response);

            var patientId = request.params(":patientId");

            Image image = objectMapper.readValue(request.body(), Image.class);

            try (var fileUploader = new FileClient(settings.getFileServer(), settings.getBucket(), new Credentials(settings.getFileServerAccessKey(), settings.getFileServerSecretKey()))) {
                var filePath = fileUploader.uploadImage(image, patientId);

                var imageResponse = new ImageResponse(filePath, image.contentType().display());

                response.status(HttpStatus.CREATED_201);
                return objectMapper.writeValueAsString(imageResponse);
            } catch (MinioException e) {
                System.out.println(e.getMessage());
                response.status(HttpStatus.INTERNAL_SERVER_ERROR_500);
                return "uploading image failed";
            }
        });

        get("/presentation/:patientId", (request, response) -> {
            response.status(HttpStatus.OK_200);
            addContent(request, response);

            var patientId = request.params(":patientId");

            try {
                response.body(jsonFhirMapper.loadPresentation(patientId));
                return response.body();
            } catch (ResourceGoneException | ResourceNotFoundException e) {
                response.status(HttpStatus.NOT_FOUND_404);
                System.out.println(e.getMessage());
                response.body("not found");
                return response.body();
            } catch (UnprocessableEntityException e) {
                response.status(HttpStatus.INTERNAL_SERVER_ERROR_500);
                response.body("multiple matches for identifier found");
                System.out.println(e.getMessage());
                return response.body();
            }
        });

        post("/presentation/:patientId", (request, response) -> {
            response.status(HttpStatus.OK_200);
            addContent(request, response);

            var presentation = objectMapper.readValue(request.body(), PresentationViewModel.class);
            var patientId = request.params(":patientId");

            try {
                jsonFhirMapper.savePresentation(patientId, presentation);
                response.body("ok");
                return response.body();
            } catch (UnprocessableEntityException e) {
                response.status(HttpStatus.UNPROCESSABLE_ENTITY_422);
                response.body("unprocessable entity");
                return response.body();
            }
        });

        options("/presentation/:patientId", (req, res) -> {
            addOptions(req, res);
            res.header("Access-Control-Allow-Methods", "DELETE");
            return res;
        });

        delete("/presentation/:patientId", (request, response) -> {
            response.status(HttpStatus.NO_CONTENT_204);
            addContent(request, response);

            var patientId = request.params(":patientId");

            try {
                jsonFhirMapper.deletePresentation(patientId);
                return response;
            } catch (UnprocessableEntityException e) {
                response.status(HttpStatus.UNPROCESSABLE_ENTITY_422);
                return "unprocessable entity";
            }
        });
    }

    /**
     * Checks if the session id is authorized to access the clinical data of the patient.
     *
     * @param req Incoming Java Spark Request
     * @return Boolean if the session if able to access the data
     */
    private static boolean validateRequest(Request req) {
        String portalDomain = settings.getPortalUrl();
        String requestedStudyId = req.queryParams("studyId");
        String validatePath = "api/studies/" + requestedStudyId + "/patients/"
                              + req.params(":patientId");
        String requestUrl = portalDomain + validatePath;

        if (requestedStudyId == null) {
            System.out.println("No query parameter studyId found - returning false\n");
            return false;
        }

        WebResource webResource = client.resource(requestUrl);
        WebResource.Builder builder = webResource.getRequestBuilder();
        builder = builder.cookie(new Cookie("JSESSIONID", req.cookies().get("JSESSIONID")));
        ClientResponse response = builder.accept("application/json").get(ClientResponse.class);

        System.out.println(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        System.out.println("Validation request for study:");
        System.out.println("Sending request at requestUrl: " + requestUrl);

        if (response.getStatus() == HttpStatus.OK_200) {
            System.out.println("Response code was good: " + response.getStatus() + "\n");
            return true;
        }
        System.out.println("Response code was: " + response.getStatus() + "\n");
        return false;
    }

    /**
     * Checks if the user is authorized to manipulate the clinical data of the patients in the requested study.
     *
     * @param req Incoming Java Spark Request
     *            + @param studyId query parameter in the request
     * @return Boolean if the session is able to access the data
     */
    private static boolean validateManipulation(Request req) {
        String requestedPatientId = req.params(":patientId");
        String requestedStudyId = req.queryParams("studyId");
        String userRoles = req.headers("X-USERROLES");
        String userLoginName = req.headers("X-USERLOGIN");

        System.out.println(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        System.out.println("Manipulation permission request:\nfrom user: " + userLoginName + ", for patientId: "
                           + requestedPatientId + "\nfound header X-USERROLES: " + userRoles
                           + "\nfound query parameter studyId: " + requestedStudyId);

        if (userRoles == null || userRoles.isEmpty() || requestedStudyId == null || requestedStudyId.isEmpty()) {
            System.out.println("Incoming user roles or studyId are null or empty - returning false\n");
            return false;
        }

        ArrayList<String> roleList = new ArrayList<>();
        Pattern p = Pattern.compile("\"([^\"]*)\"");
        Matcher m = p.matcher(userRoles);
        while (m.find()) {
            roleList.add(m.group(1));
        }

        for (String s : roleList) {
            if ((requestedStudyId != null && requestedStudyId.matches(s)) || s.equals(requestedPatientId)) {
                System.out.println("permission granted with role: " + s + "\n");
                return true;
            }
        }

        System.out.println("no matching role could be found - returning false\n");
        return false;

    }

    private static void addRes(Request req, Response res) {
        res.header("Access-Control-Allow-Origin", req.headers("Origin"));
        res.header("Vary", "Origin, Access-Control-Request-Headers");
    }

    private static void addOptions(Request req, Response res) {
        addRes(req, res);
        res.status(HttpStatus.NO_CONTENT_204);
        res.header("Access-Control-Allow-Credentials", "true");
        res.header("Access-Control-Allow-Headers", req.headers("Access-Control-Request-Headers"));
        res.header("Content-Length", "0");
        res.header("Content-Type", "");
    }

    private static void addContent(Request req, Response res) {
        addRes(req, res);
        res.type("application/json");
    }

}
