package io.pivotal.security.controller.v1;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.ParseContext;
import io.pivotal.security.data.NamedCertificateAuthorityDataService;
import io.pivotal.security.entity.NamedCertificateAuthority;
import io.pivotal.security.generator.BCCertificateGenerator;
import io.pivotal.security.mapper.CAGeneratorRequestTranslator;
import io.pivotal.security.mapper.CASetterRequestTranslator;
import io.pivotal.security.mapper.RequestTranslator;
import io.pivotal.security.service.AuditLogService;
import io.pivotal.security.service.AuditRecordParameters;
import io.pivotal.security.view.CertificateAuthority;
import io.pivotal.security.view.DataResponse;
import io.pivotal.security.view.ParameterizedValidationException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import static com.google.common.collect.Lists.newArrayList;
import static io.pivotal.security.constants.AuditingOperationCodes.AUTHORITY_ACCESS;
import static io.pivotal.security.constants.AuditingOperationCodes.AUTHORITY_UPDATE;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping(path = CaController.API_V1_CA, produces = MediaType.APPLICATION_JSON_UTF8_VALUE)
public class CaController {

  public static final String API_V1_CA = "/api/v1/ca";

  @Autowired
  ParseContext jsonPath;

  @Autowired
  NamedCertificateAuthorityDataService namedCertificateAuthorityDataService;

  private MessageSourceAccessor messageSourceAccessor;

  @Autowired
  MessageSource messageSource;

  @Autowired
  CASetterRequestTranslator caSetterRequestTranslator;

  @Autowired
  CAGeneratorRequestTranslator caGeneratorRequestTranslator;

  @Autowired
  BCCertificateGenerator certificateGenerator;

  @Autowired
  AuditLogService auditLogService;

  @PostConstruct
  public void init() {
    messageSourceAccessor = new MessageSourceAccessor(messageSource);
  }

  @SuppressWarnings("unused")
  @RequestMapping(path = "/**", method = RequestMethod.PUT)
  ResponseEntity set(InputStream requestBody, HttpServletRequest request, Authentication authentication) throws Exception {
    return auditLogService.performWithAuditing(AUTHORITY_UPDATE, new AuditRecordParameters(request, authentication), () -> {
      return storeAuthority(caPath(request), requestBody, caSetterRequestTranslator);
    });
  }

  @SuppressWarnings("unused")
  @RequestMapping(path = "/**", method = RequestMethod.POST)
  ResponseEntity generate(InputStream requestBody, HttpServletRequest request, Authentication authentication) throws Exception {
    return auditLogService.performWithAuditing(AUTHORITY_UPDATE, new AuditRecordParameters(request, authentication), () -> {
      return storeAuthority(caPath(request), requestBody, caGeneratorRequestTranslator);
    });
  }

  private ResponseEntity storeAuthority(@PathVariable String caPath, InputStream requestBody, RequestTranslator<NamedCertificateAuthority> requestTranslator) {
    DocumentContext parsedRequest = jsonPath.parse(requestBody);
    NamedCertificateAuthority namedCertificateAuthority = new NamedCertificateAuthority(caPath);
    NamedCertificateAuthority originalCertificateAuthority = namedCertificateAuthorityDataService.findMostRecentByName(caPath);

    if (originalCertificateAuthority != null) {
      originalCertificateAuthority.copyInto(namedCertificateAuthority);
    }

    try {
      requestTranslator.validateJsonKeys(parsedRequest);
      requestTranslator.populateEntityFromJson(namedCertificateAuthority, parsedRequest);
    } catch (ParameterizedValidationException ve) {
      return createParameterizedErrorResponse(ve, HttpStatus.BAD_REQUEST);
    }

    NamedCertificateAuthority saved = namedCertificateAuthorityDataService.save(namedCertificateAuthority);
    String privateKey = namedCertificateAuthorityDataService.getPrivateKeyClearText(namedCertificateAuthority);

    return new ResponseEntity<>(CertificateAuthority.fromEntity(saved, privateKey), HttpStatus.OK);
  }

  @SuppressWarnings("unused")
  @RequestMapping(path = "/**", method = RequestMethod.GET)
  public ResponseEntity getByName(HttpServletRequest request, Authentication authentication) throws Exception {
    return retrieveAuthorityWithAuditing(
        caPath(request),
        namedCertificateAuthorityDataService::findMostRecentByName,
        request,
        authentication);
  }

  @RequestMapping(path = "", method = RequestMethod.GET)
  public ResponseEntity getByIdOrName(
      @RequestParam(name = "id", defaultValue="", required = false) String id,
      @RequestParam(name = "name", defaultValue="", required = false) String name,
      HttpServletRequest request,
      Authentication authentication) throws Exception {

    boolean byName = StringUtils.isEmpty(id);
    if (byName && StringUtils.isEmpty(name)) {
      return createErrorResponse("error.no_identifier", HttpStatus.BAD_REQUEST);
    }

    String identifier = byName ? name : id;

    return retrieveAuthorityWithAuditing(
        identifier,
        getFinder(byName),
        request,
        authentication);
  }

  @ExceptionHandler({HttpMessageNotReadableException.class, ParameterizedValidationException.class, com.jayway.jsonpath.InvalidJsonException.class})
  @ResponseStatus(value = HttpStatus.BAD_REQUEST)
  public ResponseError handleHttpMessageNotReadableException() throws IOException {
    return new ResponseError(ResponseErrorType.BAD_REQUEST);
  }

  private Function<String, NamedCertificateAuthority> getFinder(boolean byName) {
    if (byName) {
      return namedCertificateAuthorityDataService::findMostRecentByName;
    } else {
      return namedCertificateAuthorityDataService::findOneByUuid;
    }
  }

  private ResponseEntity retrieveAuthorityWithAuditing(
      String identifier,
      Function<String, NamedCertificateAuthority> finder,
      HttpServletRequest request,
      Authentication authentication) throws Exception {
    return auditLogService.performWithAuditing(
        AUTHORITY_ACCESS,
        new AuditRecordParameters(request, authentication),
        () -> {
          NamedCertificateAuthority namedAuthority = finder.apply(identifier);

          if (namedAuthority == null) {
            return createErrorResponse("error.ca_not_found", HttpStatus.NOT_FOUND);
          }

          // TODO: when we fetch multiple this will need to happen in a loop...
          String privateKeyClearText = namedCertificateAuthorityDataService.getPrivateKeyClearText(namedAuthority);
          CertificateAuthority ca = new CertificateAuthority(namedAuthority, privateKeyClearText);
          List<CertificateAuthority> caList = newArrayList(ca);

          return new ResponseEntity<>(new DataResponse(caList), HttpStatus.OK);
        });
  }

  private String caPath(HttpServletRequest request) {
    return request.getRequestURI().replace(API_V1_CA + "/", "");
  }

  private ResponseEntity createErrorResponse(String key, HttpStatus status) {
    String errorMessage = messageSourceAccessor.getMessage(key);
    return new ResponseEntity<>(Collections.singletonMap("error", errorMessage), status);
  }

  private ResponseEntity createParameterizedErrorResponse(ParameterizedValidationException exception, HttpStatus status) {
    String errorMessage = messageSourceAccessor.getMessage(exception.getMessage(), exception.getParameters());
    return new ResponseEntity<>(Collections.singletonMap("error", errorMessage), status);
  }
}
