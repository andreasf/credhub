package io.pivotal.security.request;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.hibernate.validator.constraints.NotEmpty;

import java.util.ArrayList;
import java.util.List;

@JsonTypeInfo(
    use         = JsonTypeInfo.Id.NAME,
    include     = JsonTypeInfo.As.PROPERTY,
    property    = "type",
    visible     = true
)
@JsonSubTypes({
    @JsonSubTypes.Type(name = "password",     value = PasswordSetRequest.class),
    @JsonSubTypes.Type(name = "value",        value = ValueSetRequest.class),
    @JsonSubTypes.Type(name = "certificate",  value = CertificateSetRequest.class),
    @JsonSubTypes.Type(name = "json",         value = JsonSetRequest.class),
    @JsonSubTypes.Type(name = "ssh",          value = SshSetRequest.class),
    @JsonSubTypes.Type(name = "rsa",          value = RsaSetRequest.class)
})
public abstract class BaseSecretSetRequest extends BaseSecretRequest {
  @NotEmpty(message = "error.invalid_type_with_set_prompt")
  private String type;
  private List<AccessControlEntry> accessControlEntries = new ArrayList<>();

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public List<AccessControlEntry> getAccessControlEntries() {
    return accessControlEntries;
  }

  public void setAccessControlEntries(List<AccessControlEntry> accessControlEntries) {
    this.accessControlEntries = accessControlEntries;
  }
}