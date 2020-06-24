package fhirspark;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.gclient.ReferenceClientParam;
import ca.uhn.fhir.rest.gclient.TokenClientParam;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fhirspark.adapter.DrugAdapter;
import fhirspark.adapter.GeneticAlternationsAdapter;
import fhirspark.adapter.SpecimenAdapter;
import fhirspark.resolver.OncoKbDrug;
import fhirspark.resolver.PubmedPublication;
import fhirspark.restmodel.CbioportalRest;
import fhirspark.restmodel.ClinicalDatum;
import fhirspark.restmodel.GeneticAlteration;
import fhirspark.restmodel.Mtb;
import fhirspark.restmodel.Reasoning;
import fhirspark.restmodel.TherapyRecommendation;
import fhirspark.restmodel.Treatment;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.hl7.fhir.instance.model.api.IAnyResource;
import org.hl7.fhir.r4.model.Annotation;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.CarePlan;
import org.hl7.fhir.r4.model.CarePlan.CarePlanActivityComponent;
import org.hl7.fhir.r4.model.CarePlan.CarePlanIntent;
import org.hl7.fhir.r4.model.CarePlan.CarePlanStatus;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.DiagnosticReport.DiagnosticReportStatus;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Identifier.IdentifierUse;
import org.hl7.fhir.r4.model.MedicationStatement;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Observation.ObservationComponentComponent;
import org.hl7.fhir.r4.model.Observation.ObservationStatus;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.RelatedArtifact;
import org.hl7.fhir.r4.model.RelatedArtifact.RelatedArtifactType;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.Specimen;
import org.hl7.fhir.r4.model.Task;
import org.hl7.fhir.r4.model.Task.TaskIntent;
import org.hl7.fhir.r4.model.Task.TaskStatus;
import org.hl7.fhir.r4.model.codesystems.ObservationCategory;

public class JsonFhirMapper {

    private static String uriLOINC = "http://loinc.org";
    private static String uriPATIENT = "https://cbioportal.org/patient/";
    private static String uriRECOMMENDEDACTION = 
            "http://hl7.org/fhir/uv/genomics-reporting/StructureDefinition/RecommendedAction";
    private static String uriFOLLOWUP =
            "http://hl7.org/fhir/uv/genomics-reporting/StructureDefinition/task-rec-followup";
    private static String uriRELATEDARTIFACT =
            "http://hl7.org/fhir/uv/genomics-reporting/StructureDefinition/RelatedArtifact";
    private static String uriMEDICATIONCHANGE =
            "http://hl7.org/fhir/uv/genomics-reporting/StructureDefinition/task-med-chg";
    private static String uriPUBMED = "https://www.ncbi.nlm.nih.gov/pubmed/";
    private static String uriNCIT = "http://ncithesaurus-stage.nci.nih.gov";
    private static String uriTHERAPYRECOMMENDATION = "https://cbioportal.org/therapyrecommendation/";
    private static String uriGENOMICSREPORT =
            "http://hl7.org/fhir/uv/genomics-reporting/StructureDefinition/genomics-report";
    private static String uriGENOMIC = "http://terminology.hl7.org/CodeSystem/v2-0074";

    private FhirContext ctx = FhirContext.forR4();
    private IGenericClient client;
    private ObjectMapper objectMapper = new ObjectMapper(new JsonFactory());
    private OncoKbDrug drugResolver = new OncoKbDrug();
    private PubmedPublication pubmedResolver = new PubmedPublication();

    private GeneticAlternationsAdapter geneticAlterationsAdapter = new GeneticAlternationsAdapter();
    private DrugAdapter drugAdapter = new DrugAdapter();
    private SpecimenAdapter specimenAdapter = new SpecimenAdapter();

    public JsonFhirMapper(Settings settings) {
        this.client = ctx.newRestfulGenericClient(settings.getFhirDbBase());
    }

    public String toJson(String patientId) throws JsonProcessingException {
        List<Mtb> mtbs = new ArrayList<Mtb>();
        Bundle bPatient = (Bundle) client.search().forResource(Patient.class)
                .where(new TokenClientParam("identifier").exactly().systemAndCode(uriPATIENT, patientId)).prettyPrint()
                .execute();
        Patient fhirPatient = (Patient) bPatient.getEntryFirstRep().getResource();

        if (fhirPatient == null) {
            return "{}";
        }

        Bundle bDiagnosticReports = (Bundle) client.search().forResource(DiagnosticReport.class)
                .where(new ReferenceClientParam("subject").hasId(harmonizeId(fhirPatient))).prettyPrint().execute();

        List<BundleEntryComponent> diagnosticReports = bDiagnosticReports.getEntry();

        for (int i = 0; i < diagnosticReports.size(); i++) {
            DiagnosticReport diagnosticReport = (DiagnosticReport) diagnosticReports.get(i).getResource();

            Mtb mtb = new Mtb().withTherapyRecommendations(new ArrayList<TherapyRecommendation>())
                    .withSamples(new ArrayList<String>());
            for (Mtb mtbCandidate : mtbs) {
                mtb = mtbCandidate.getId().equals("mtb_" + patientId + "_" + diagnosticReport.getIssued().getTime())
                        ? mtbCandidate
                        : new Mtb().withTherapyRecommendations(new ArrayList<TherapyRecommendation>())
                                .withSamples(new ArrayList<String>());
            }
            if (!mtbs.contains(mtb)) {
                mtbs.add(mtb);
            }

            if (diagnosticReport.hasPerformer()) {
                Bundle b2 = (Bundle) client.search().forResource(Practitioner.class).where(
                        new TokenClientParam("_id").exactly().code(diagnosticReport.getPerformerFirstRep().getId()))
                        .prettyPrint().execute();
                Practitioner author = (Practitioner) b2.getEntryFirstRep().getResource();
                mtb.setAuthor(author.getIdentifierFirstRep().getValue());
            }

            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd");
            mtb.setDate(f.format(diagnosticReport.getEffectiveDateTimeType().toCalendar().getTime()));

            mtb.setGeneralRecommendation(diagnosticReport.getConclusion());

            // GENETIC COUNSELING HERE

            mtb.setId("mtb_" + patientId + "_" + diagnosticReport.getIssued().getTime());

            if (diagnosticReport.hasStatus()) {
                switch (diagnosticReport.getStatus().toCode()) {
                    case "partial":
                        mtb.setMtbState("DRAFT");
                        break;
                    case "final":
                        mtb.setMtbState("COMPLETED");
                        break;
                    case "cancelled":
                        mtb.setMtbState("ARCHIVED");
                        break;
                }
            }

            // REBIOPSY HERE

            mtb.getSamples().clear();
            for (Reference specimen : diagnosticReport.getSpecimen()) {
                mtb.getSamples().add(((Specimen) specimen.getResource()).getIdentifierFirstRep().getValue());
            }

            TherapyRecommendation therapyRecommendation = new TherapyRecommendation()
                    .withComment(new ArrayList<String>()).withReasoning(new Reasoning());
            mtb.getTherapyRecommendations().add(therapyRecommendation);
            List<ClinicalDatum> clinicalData = new ArrayList<ClinicalDatum>();
            List<GeneticAlteration> geneticAlterations = new ArrayList<GeneticAlteration>();
            therapyRecommendation.getReasoning().withClinicalData(clinicalData)
                    .withGeneticAlterations(geneticAlterations);

            therapyRecommendation.setAuthor(mtb.getAuthor());

            // COMMENTS GET WITHIN MEDICATION

            therapyRecommendation.setId(diagnosticReport.getIdentifier().get(0).getValue());

            // PUT CLINICAL DATA HERE

            for (Reference reference : diagnosticReport.getResult()) {
                switch (reference.getResource().getMeta().getProfile().get(0).getValue()) {
                    case "http://hl7.org/fhir/uv/genomics-reporting/StructureDefinition/variant":
                        GeneticAlteration g = new GeneticAlteration();
                        ((Observation) reference.getResource()).getComponent().forEach(variant -> {
                            switch (variant.getCode().getCodingFirstRep().getCode()) {
                                case "48005-3":
                                    g.setAlteration(variant.getValueCodeableConcept().getCodingFirstRep().getCode()
                                            .replaceFirst("p.", ""));
                                    break;
                                case "81252-9":
                                    g.setEntrezGeneId(Integer
                                            .valueOf(variant.getValueCodeableConcept().getCodingFirstRep().getCode()));
                                    break;
                                case "48018-6":
                                    g.setHugoSymbol(variant.getValueCodeableConcept().getCodingFirstRep().getDisplay());
                                    break;
                            }
                        });
                        geneticAlterations.add(g);
                        break;
                    case "http://hl7.org/fhir/uv/genomics-reporting/StructureDefinition/medication-efficacy":
                        ((Observation) reference.getResource()).getComponent().forEach(result -> {
                            if (result.getCode().getCodingFirstRep().getCode().equals("93044-6")) {
                                therapyRecommendation.setEvidenceLevel(
                                        result.getValueCodeableConcept().getCodingFirstRep().getCode());
                            }
                        });
                }
            }

            List<Treatment> treatments = new ArrayList<Treatment>();
            therapyRecommendation.setTreatments(treatments);
            List<Extension> recommendedActionReferences = diagnosticReport.getExtensionsByUrl(uriRECOMMENDEDACTION);
            for (Extension recommendedActionReference : recommendedActionReferences) {

                Task t = (Task) ((Reference) recommendedActionReference.getValue()).getResource();
                if (t != null) {
                    assert t.getMeta().getProfile().get(0).getValue().equals(uriFOLLOWUP);
                    Coding c = t.getCode().getCodingFirstRep();
                    switch (c.getCode()) {
                        case "LA14021-2":
                            mtb.setRebiopsyRecommendation(true);
                            break;
                        case "LA14020-4":
                            mtb.setGeneticCounselingRecommendation(true);
                            break;
                    }
                } else {
                    Bundle bRecommendedAction = (Bundle) client.search().forResource(Task.class)
                            .where(new TokenClientParam("_id").exactly()
                                    .code(((Reference) recommendedActionReference.getValue()).getReference()))
                            .prettyPrint().execute();
                    MedicationStatement medicationStatement = (MedicationStatement) ((Task) bRecommendedAction
                            .getEntryFirstRep().getResource()).getFocus().getResource();
                    Coding drug = medicationStatement.getMedicationCodeableConcept().getCodingFirstRep();
                    treatments.add(new Treatment().withNcitCode(drug.getCode()).withName(drug.getDisplay()));

                    for (Annotation a : medicationStatement.getNote()) {
                        therapyRecommendation.getComment().add(a.getText());
                    }
                }
            }

            List<fhirspark.restmodel.Reference> references = new ArrayList<fhirspark.restmodel.Reference>();
            for (Extension relatedArtifact : diagnosticReport.getExtensionsByUrl(uriRELATEDARTIFACT)) {
                if (((RelatedArtifact) relatedArtifact.getValue()).getType() == RelatedArtifactType.CITATION) {
                    references.add(new fhirspark.restmodel.Reference()
                            .withPmid(Integer.valueOf(((RelatedArtifact) relatedArtifact.getValue()).getUrl()
                                    .replaceFirst(uriPUBMED, "")))
                            .withName(((RelatedArtifact) relatedArtifact.getValue()).getCitation()));
                }
            }

            therapyRecommendation.setReferences(references);

        }

        return this.objectMapper.writeValueAsString(new CbioportalRest().withId(patientId).withMtbs(mtbs));

    }

    public void addOrEditMtb(String patientId, List<Mtb> mtbs) throws DataFormatException, IOException {

        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.TRANSACTION);

        Reference fhirPatient = getOrCreatePatient(bundle, patientId);

        for (Mtb mtb : mtbs) {

            for (TherapyRecommendation therapyRecommendation : mtb.getTherapyRecommendations()) {

                DiagnosticReport diagnosticReport = new DiagnosticReport();
                diagnosticReport.getMeta().addProfile(uriGENOMICSREPORT);
                diagnosticReport.setId(IdType.newRandomUuid());
                diagnosticReport.setSubject(fhirPatient);
                diagnosticReport.addCategory().addCoding(new Coding().setSystem(uriGENOMIC).setCode("GE"));
                diagnosticReport.getCode()
                        .addCoding(new Coding(uriLOINC, "81247-9", "Master HL7 genetic variant reporting panel"));

                CarePlan carePlan = new CarePlan();
                carePlan.setId(IdType.newRandomUuid());
                carePlan.setSubject(fhirPatient);
                carePlan.setIntent(CarePlanIntent.PROPOSAL);
                carePlan.setStatus(CarePlanStatus.ACTIVE);
                carePlan.setAuthor(getOrCreatePractitioner(bundle, therapyRecommendation.getAuthor()));
                carePlan.getSupportingInfo().add(new Reference(diagnosticReport));

                // MTB SECTION

                diagnosticReport.addPerformer(getOrCreatePractitioner(bundle, therapyRecommendation.getAuthor()));

                diagnosticReport.getEffectiveDateTimeType().fromStringValue(mtb.getDate());

                diagnosticReport.setConclusion(mtb.getGeneralRecommendation());

                if (mtb.getGeneticCounselingRecommendation() != null && mtb.getGeneticCounselingRecommendation()) {
                    Task t = new Task();
                    t.getMeta().addProfile(uriFOLLOWUP);
                    t.setFor(fhirPatient);
                    t.setStatus(TaskStatus.REQUESTED).setIntent(TaskIntent.PROPOSAL);
                    t.getCode().setText("Recommended follow-up")
                            .addCoding(new Coding(uriLOINC, "LA14020-4", "Genetic counseling recommended"));
                    Extension ex = new Extension().setUrl(uriRECOMMENDEDACTION);
                    ex.setValue(new Reference(t));
                    diagnosticReport.addExtension(ex);
                    carePlan.getActivity().add(new CarePlanActivityComponent().setReference(new Reference(t)));
                }

                assert mtb.getId().startsWith("mtb_" + patientId + "_");
                diagnosticReport.setIssued(new Date(Long.valueOf(mtb.getId().replace("mtb_" + patientId + "_", ""))));

                if (mtb.getMtbState() != null) {
                    switch (mtb.getMtbState().toUpperCase()) {
                        case "DRAFT":
                            diagnosticReport.setStatus(DiagnosticReportStatus.PARTIAL);
                            break;
                        case "COMPLETED":
                            diagnosticReport.setStatus(DiagnosticReportStatus.FINAL);
                            break;
                        case "ARCHIVED":
                            diagnosticReport.setStatus(DiagnosticReportStatus.CANCELLED);
                    }
                } else {
                    diagnosticReport.setStatus(DiagnosticReportStatus.PARTIAL);
                }

                if (mtb.getRebiopsyRecommendation() != null && mtb.getRebiopsyRecommendation()) {
                    Task t = new Task();
                    t.getMeta().addProfile(uriFOLLOWUP);
                    t.setFor(fhirPatient);
                    t.setStatus(TaskStatus.REQUESTED).setIntent(TaskIntent.PROPOSAL);
                    t.getCode().setText("Recommended follow-up")
                            .addCoding(new Coding(uriLOINC, "LA14021-2", "Confirmatory testing recommended"));
                    Extension ex = new Extension().setUrl(uriRECOMMENDEDACTION);
                    ex.setValue(new Reference(t));
                    diagnosticReport.addExtension(ex);
                }

                mtb.getSamples().forEach(sample -> diagnosticReport
                        .addSpecimen(new Reference(specimenAdapter.process(fhirPatient, sample))));

                // THERAPYRECOMMENDATION SECTION

                // AUTHOR ALREADY SET BY MTB

                // COMMENTS SET WITH MEDICATION

                Observation efficacyObservation = new Observation();
                diagnosticReport.addResult(new Reference(efficacyObservation));
                efficacyObservation.getMeta().addProfile(
                        "http://hl7.org/fhir/uv/genomics-reporting/StructureDefinition/medication-efficacy");
                efficacyObservation.setStatus(ObservationStatus.FINAL);
                efficacyObservation.addCategory().addCoding(new Coding(ObservationCategory.LABORATORY.getSystem(),
                        ObservationCategory.LABORATORY.toCode(), ObservationCategory.LABORATORY.getDisplay()));
                efficacyObservation.getValueCodeableConcept()
                        .addCoding(new Coding(uriLOINC, "LA9661-5", "Presumed responsive"));
                efficacyObservation.getCode()
                        .addCoding(new Coding(uriLOINC, "51961-1", "Genetic variation's effect on drug efficacy"));
                ObservationComponentComponent evidenceComponent = efficacyObservation.addComponent();
                evidenceComponent.getCode().addCoding(new Coding(uriLOINC, "93044-6", "Level of evidence"));
                evidenceComponent.getValueCodeableConcept().addCoding(new Coding("https://cbioportal.org/evidence/BW/",
                        therapyRecommendation.getEvidenceLevel(), therapyRecommendation.getEvidenceLevel()));

                diagnosticReport.addIdentifier().setSystem(uriTHERAPYRECOMMENDATION)
                        .setValue(therapyRecommendation.getId());

                if (therapyRecommendation.getReasoning().getClinicalData() != null) {
                    therapyRecommendation.getReasoning().getClinicalData().forEach(clinical -> {
                        try {
                            Method m = Class.forName("fhirspark.adapter.clinicaldata." + clinical.getAttributeId())
                                    .getMethod("process", ClinicalDatum.class);
                            Resource clinicalFhir = (Resource) m.invoke(null, clinical);
                            diagnosticReport.addResult(new Reference(clinicalFhir));
                        } catch (ClassNotFoundException e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        } catch (NoSuchMethodException e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        } catch (IllegalAccessException e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        } catch (InvocationTargetException e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        }
                    });
                }

                if (therapyRecommendation.getReasoning().getGeneticAlterations() != null) {
                    therapyRecommendation.getReasoning().getGeneticAlterations().forEach(geneticAlteration -> {
                        Resource geneticVariant = geneticAlterationsAdapter.process(geneticAlteration);
                        diagnosticReport.addResult(new Reference(geneticVariant));
                        efficacyObservation.addDerivedFrom(new Reference(geneticVariant));
                    });
                }

                for (fhirspark.restmodel.Reference reference : therapyRecommendation.getReferences()) {
                    String title = reference.getName() != null ? reference.getName()
                            : pubmedResolver.resolvePublication(reference.getPmid());
                    Extension ex = new Extension().setUrl(uriRELATEDARTIFACT);
                    RelatedArtifact relatedArtifact = new RelatedArtifact().setType(RelatedArtifactType.CITATION)
                            .setUrl(uriPUBMED + reference.getPmid()).setCitation(title);
                    ex.setValue(relatedArtifact);
                    diagnosticReport.addExtension(ex);
                }

                for (Treatment treatment : therapyRecommendation.getTreatments()) {
                    Task medicationChange = new Task().setStatus(TaskStatus.REQUESTED).setIntent(TaskIntent.PROPOSAL)
                            .setFor(fhirPatient);
                    medicationChange.setId(IdType.newRandomUuid());
                    medicationChange.getMeta().addProfile(uriMEDICATIONCHANGE);

                    MedicationStatement ms = drugAdapter.process(fhirPatient, treatment);

                    medicationChange.getCode()
                            .addCoding(new Coding(uriLOINC, "LA26421-0", "Consider alternative medication"));
                    medicationChange.setFocus(new Reference(ms));
                    String ncit = ms.getMedicationCodeableConcept().getCodingFirstRep().getCode();
                    medicationChange.addIdentifier(new Identifier().setSystem(uriNCIT).setValue(ncit));

                    for (String comment : therapyRecommendation.getComment()) {
                        ms.getNote().add(new Annotation().setText(comment));
                    }

                    Extension ex = new Extension().setUrl(uriRECOMMENDEDACTION);
                    ex.setValue(new Reference(medicationChange));
                    diagnosticReport.addExtension(ex);

                    bundle.addEntry().setFullUrl(medicationChange.getIdElement().getValue())
                            .setResource(medicationChange).getRequest()
                            .setUrl("Task?identifier=" + uriNCIT + "|" + ncit + "&subject="
                                    + fhirPatient.getResource().getIdElement())
                            .setIfNoneExist("identifier=Task?identifier=" + uriNCIT + "|" + ncit + "&subject="
                                    + fhirPatient.getResource().getIdElement())
                            .setMethod(Bundle.HTTPVerb.PUT);

                    ObservationComponentComponent assessed = efficacyObservation.addComponent();
                    assessed.getCode().addCoding(new Coding(uriLOINC, "51963-7", "Medication assessed [ID]"));
                    assessed.setValue(ms.getMedicationCodeableConcept());

                    carePlan.addActivity().setReference(new Reference(medicationChange));
                }

                bundle.addEntry().setFullUrl(diagnosticReport.getIdElement().getValue()).setResource(diagnosticReport)
                        .getRequest()
                        .setUrl("DiagnosticReport?identifier=" + uriTHERAPYRECOMMENDATION + "|"
                                + therapyRecommendation.getId())
                        .setIfNoneExist("identifier=" + uriTHERAPYRECOMMENDATION + "|" + therapyRecommendation.getId())
                        .setMethod(Bundle.HTTPVerb.PUT);

            }

        }

        try {
            Bundle resp = client.transaction().withBundle(bundle).execute();

            // Log the response
            System.out.println(ctx.newJsonParser().setPrettyPrint(true).encodeResourceToString(resp));
        } catch (UnprocessableEntityException entityException) {
            FileWriter f = new FileWriter("error.json");
            f.write(entityException.getResponseBody());
            f.close();
        }

    }

    public void deleteTherapyRecommendation(String patientId, String therapyRecommendationId) {
        assert therapyRecommendationId.startsWith(patientId);
        client.delete().resourceConditionalByUrl("CarePlan?identifier=" + uriPATIENT + "|" + therapyRecommendationId)
                .execute();
    }

    private Reference getOrCreatePatient(Bundle b, String patientId) {

        Patient patient = new Patient();
        patient.setId(IdType.newRandomUuid());
        patient.getIdentifierFirstRep().setSystem(uriPATIENT).setValue(patientId);
        patient.getIdentifierFirstRep().setUse(IdentifierUse.USUAL);
        patient.getIdentifierFirstRep().getType().addCoding().setSystem("http://terminology.hl7.org/CodeSystem/v2-0203")
                .setCode("MR");
        b.addEntry().setFullUrl(patient.getIdElement().getValue()).setResource(patient).getRequest()
                .setUrl("Patient?identifier=" + uriPATIENT + "|" + patientId)
                .setIfNoneExist("identifier=" + uriPATIENT + "|" + patientId).setMethod(Bundle.HTTPVerb.PUT);

        return new Reference(patient);
    }

    private Reference getOrCreatePractitioner(Bundle b, String credentials) {

        Practitioner practitioner = new Practitioner();
        practitioner.setId(IdType.newRandomUuid());
        practitioner.addIdentifier(new Identifier().setSystem(uriPATIENT).setValue(credentials));
        b.addEntry().setFullUrl(practitioner.getIdElement().getValue()).setResource(practitioner).getRequest()
                .setUrl("Practitioner?identifier=" + uriPATIENT + "|" + credentials)
                .setIfNoneExist("identifier=" + uriPATIENT + "|" + credentials).setMethod(Bundle.HTTPVerb.PUT);

        return new Reference(practitioner);

    }

    private String harmonizeId(IAnyResource resource) {
        if (resource.getIdElement().getValue().startsWith("urn:uuid:")) {
            return resource.getIdElement().getValue();
        } else {
            return resource.getIdElement().getResourceType() + "/" + resource.getIdElement().getIdPart();
        }
    }

}
