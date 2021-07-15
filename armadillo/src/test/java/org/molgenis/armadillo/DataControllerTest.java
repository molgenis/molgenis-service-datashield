package org.molgenis.armadillo;

import static java.time.Instant.now;
import static java.util.Collections.emptyList;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static org.apache.commons.lang3.builder.EqualsBuilder.reflectionEquals;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.molgenis.armadillo.ArmadilloUtils.serializeExpression;
import static org.molgenis.armadillo.DataController.TABLE_RESOURCE_REGEX;
import static org.obiba.datashield.core.DSMethodType.AGGREGATE;
import static org.obiba.datashield.core.DSMethodType.ASSIGN;
import static org.springframework.http.MediaType.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.molgenis.armadillo.audit.AuditEventPublisher;
import org.molgenis.armadillo.command.ArmadilloCommandDTO;
import org.molgenis.armadillo.command.Commands;
import org.molgenis.armadillo.command.Commands.ArmadilloCommandStatus;
import org.molgenis.armadillo.exceptions.ExpressionException;
import org.molgenis.armadillo.minio.ArmadilloStorageService;
import org.molgenis.armadillo.model.Workspace;
import org.molgenis.armadillo.service.DataShieldEnvironmentHolder;
import org.molgenis.armadillo.service.ExpressionRewriter;
import org.molgenis.r.model.RPackage;
import org.obiba.datashield.core.DSEnvironment;
import org.obiba.datashield.core.DSMethod;
import org.obiba.datashield.core.impl.DefaultDSMethod;
import org.obiba.datashield.r.expr.v2.ParseException;
import org.rosuda.REngine.REXP;
import org.rosuda.REngine.REXPDouble;
import org.rosuda.REngine.REXPRaw;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.audit.AuditEvent;
import org.springframework.boot.actuate.audit.listener.AuditApplicationEvent;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(DataController.class)
@ExtendWith(MockitoExtension.class)
@ActiveProfiles("test")
@Import(AuditEventPublisher.class)
class DataControllerTest {

  static RPackage BASE =
      RPackage.builder()
          .setName("base")
          .setVersion("3.6.1")
          .setBuilt("3.6.1")
          .setLibPath("/usr/local/lib/R/site-library")
          .build();

  static RPackage DESC =
      RPackage.builder()
          .setName("desc")
          .setVersion("1.2.0")
          .setBuilt("3.6.1")
          .setLibPath("/usr/local/lib/R/site-library")
          .build();

  @Autowired private MockMvc mockMvc;
  @Autowired AuditEventPublisher auditEventPublisher;
  @MockBean private ExpressionRewriter expressionRewriter;
  @MockBean private Commands commands;
  @MockBean private ArmadilloStorageService armadilloStorage;
  @MockBean private DataShieldEnvironmentHolder environments;
  @MockBean private ApplicationEventPublisher applicationEventPublisher;
  @Mock private REXP rexp;
  @Mock private DSEnvironment assignEnvironment;

  @Mock(lenient = true)
  private Clock clock;

  @Captor private ArgumentCaptor<AuditApplicationEvent> eventCaptor;
  MockHttpSession session = new MockHttpSession();
  private String sessionId;
  private final Instant instant = Instant.now();

  @BeforeEach
  public void setup() {
    auditEventPublisher.setClock(clock);
    auditEventPublisher.setApplicationEventPublisher(applicationEventPublisher);
    when(clock.instant()).thenReturn(instant);
    sessionId = session.changeSessionId();
  }

  private void expectAuditEvent(AuditEvent expectedEvent) {
    verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
    final var auditEvent = eventCaptor.getValue().getAuditEvent();
    final var equal = reflectionEquals(auditEvent, expectedEvent);
    if (!equal) {
      System.out.println(auditEvent);
      System.out.println(expectedEvent);
    }
    assertTrue(equal);
  }

  @Test
  @WithMockUser
  void testGetPackages() throws Exception {
    when(clock.instant()).thenReturn(instant);
    when(commands.getPackages()).thenReturn(completedFuture(List.of(BASE, DESC)));
    mockMvc
        .perform(get("/packages").session(session))
        .andExpect(status().isOk())
        .andExpect(content().contentType(APPLICATION_JSON))
        .andExpect(content().json("[{\"name\": \"base\"}, {\"name\": \"desc\"}]"));
    verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
    expectAuditEvent(
        new AuditEvent(
            instant,
            "user",
            "GET_PACKAGES",
            Map.of("sessionId", sessionId, "roles", List.of("ROLE_USER"))));
  }

  @Test
  @WithMockUser
  void getGetTables() throws Exception {
    when(armadilloStorage.listProjects()).thenReturn(List.of("gecko"));
    when(armadilloStorage.listTables("gecko"))
        .thenReturn(List.of("gecko/1_1_core_2_1/core", "gecko/1_1_core_2_2/core"));

    mockMvc
        .perform(get("/tables").session(session))
        .andExpect(status().isOk())
        .andExpect(content().contentType(APPLICATION_JSON))
        .andExpect(content().json("[\"gecko/1_1_core_2_1/core\",\"gecko/1_1_core_2_2/core\"]"));

    expectAuditEvent(
        new AuditEvent(
            instant,
            "user",
            "GET_TABLES",
            Map.of("sessionId", sessionId, "roles", List.of("ROLE_USER"))));
  }

  @Test
  @WithMockUser
  void testTableExists() throws Exception {
    when(armadilloStorage.tableExists("gecko", "1_1_outcome_2_0/core")).thenReturn(true);
    mockMvc
        .perform(head("/tables/gecko/1_1_outcome_2_0/core").session(session))
        .andExpect(status().isOk());

    expectAuditEvent(
        new AuditEvent(
            instant,
            "user",
            "TABLE_EXISTS",
            Map.of(
                "sessionId",
                sessionId,
                "roles",
                List.of("ROLE_USER"),
                "project",
                "gecko",
                "folder",
                "1_1_outcome_2_0",
                "table",
                "core")));
  }

  @Test
  @WithMockUser
  void testTableNotFound() throws Exception {
    when(armadilloStorage.tableExists("gecko", "1_1_outcome_2_0/core")).thenReturn(false);
    mockMvc
        .perform(head("/tables/gecko/1_1_outcome_2_0/core").session(session))
        .andExpect(status().isNotFound());

    expectAuditEvent(
        new AuditEvent(
            instant,
            "user",
            "TABLE_EXISTS",
            Map.of(
                "sessionId",
                sessionId,
                "roles",
                List.of("ROLE_USER"),
                "project",
                "gecko",
                "folder",
                "1_1_outcome_2_0",
                "table",
                "core")));
  }

  @Test
  @WithMockUser
  void getGetSymbols() throws Exception {
    when(commands.evaluate("base::ls()")).thenReturn(completedFuture(rexp));
    when(rexp.asStrings()).thenReturn(new String[] {"D"});

    mockMvc
        .perform(get("/symbols").session(session))
        .andExpect(status().isOk())
        .andExpect(content().contentType(APPLICATION_JSON))
        .andExpect(content().json("[\"D\"]"));

    expectAuditEvent(
        new AuditEvent(
            instant,
            "user",
            "GET_ASSIGNED_SYMBOLS",
            Map.of("sessionId", sessionId, "roles", List.of("ROLE_USER"))));
  }

  @Test
  @WithMockUser
  void deleteSymbol() throws Exception {
    when(commands.evaluate("base::rm(D)")).thenReturn(completedFuture(null));
    mockMvc.perform(delete("/symbols/D").session(session)).andExpect(status().isOk());

    expectAuditEvent(
        new AuditEvent(
            instant,
            "user",
            "REMOVE_SYMBOL",
            Map.of("sessionId", sessionId, "roles", List.of("ROLE_USER"), "symbol", "D")));
  }

  @Test
  @WithMockUser
  void getAssignMethods() throws Exception {
    when(environments.getEnvironment(ASSIGN)).thenReturn(assignEnvironment);
    DSMethod method = new DefaultDSMethod("meanDS", "dsBase::meanDS", "dsBase", "1.2.3");
    when(assignEnvironment.getMethods()).thenReturn(List.of(method));

    mockMvc
        .perform(get("/methods/assign").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].name").value("meanDS"))
        .andExpect(jsonPath("$[0].function").value("dsBase::meanDS"))
        .andExpect(jsonPath("$[0].package").value("dsBase"))
        .andExpect(jsonPath("$[0].version").value("1.2.3"));

    expectAuditEvent(
        new AuditEvent(
            instant,
            "user",
            "GET_ASSIGN_METHODS",
            Map.of("sessionId", sessionId, "roles", List.of("ROLE_USER"))));
  }

  @Test
  @WithMockUser
  void getAggregateMethods() throws Exception {
    when(environments.getEnvironment(AGGREGATE)).thenReturn(assignEnvironment);
    DSMethod method = new DefaultDSMethod("ls", "base::ls", "base", null);
    when(assignEnvironment.getMethods()).thenReturn(List.of(method));

    mockMvc
        .perform(get("/methods/aggregate").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].name").value("ls"))
        .andExpect(jsonPath("$[0].function").value("base::ls"))
        .andExpect(jsonPath("$[0].package").value("base"))
        .andExpect(jsonPath("$[0].version", nullValue()));

    expectAuditEvent(
        new AuditEvent(
            instant,
            "user",
            "GET_AGGREGATE_METHODS",
            Map.of("sessionId", sessionId, "roles", List.of("ROLE_USER"))));
  }

  @Test
  @WithMockUser
  void testGetLastResultNoResult() throws Exception {
    MvcResult result =
        mockMvc.perform(get("/lastresult").accept(APPLICATION_OCTET_STREAM)).andReturn();
    mockMvc.perform(asyncDispatch(result)).andExpect(status().isNotFound());
  }

  @Test
  @WithMockUser
  void testGetLastResult() throws Exception {
    byte[] bytes = {0x0, 0x1, 0x2};
    when(commands.getLastExecution()).thenReturn(Optional.of(completedFuture(new REXPRaw(bytes))));

    MvcResult result =
        mockMvc.perform(get("/lastresult").accept(APPLICATION_OCTET_STREAM)).andReturn();
    mockMvc
        .perform(asyncDispatch(result))
        .andExpect(status().isOk())
        .andExpect(content().contentType(APPLICATION_OCTET_STREAM))
        .andExpect(content().bytes(bytes));

    verifyNoInteractions(applicationEventPublisher);
  }

  @Test
  @WithMockUser
  void testGetLastCommandNotFound() throws Exception {
    mockMvc.perform(get("/lastcommand").accept(APPLICATION_JSON)).andExpect(status().isNotFound());

    verifyNoInteractions(applicationEventPublisher);
  }

  @Test
  @WithMockUser
  void testGetLastCommand() throws Exception {
    ArmadilloCommandDTO command =
        ArmadilloCommandDTO.builder()
            .createDate(now())
            .status(ArmadilloCommandStatus.PENDING)
            .expression("expression")
            .id(UUID.randomUUID())
            .withResult(true)
            .build();
    when(commands.getLastCommand()).thenReturn(Optional.of(command));

    mockMvc
        .perform(get("/lastcommand").accept(APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(content().contentType(APPLICATION_JSON))
        .andExpect(jsonPath("status").value("PENDING"));

    verifyNoInteractions(applicationEventPublisher);
  }

  @Test
  @WithMockUser(username = "henk")
  void testDeleteWorkspace() throws Exception {
    mockMvc.perform(delete("/workspaces/test").session(session)).andExpect(status().isOk());

    verify(armadilloStorage).removeWorkspace(any(Principal.class), eq("test"));

    expectAuditEvent(
        new AuditEvent(
            instant,
            "henk",
            "DELETE_USER_WORKSPACE",
            Map.of("sessionId", sessionId, "roles", List.of("ROLE_USER"), "id", "test")));
  }

  @Test
  @WithMockUser(username = "henk")
  void testSaveWorkspace() throws Exception {
    when(commands.saveWorkspace(any(Principal.class), eq("servername:test_dash")))
        .thenReturn(completedFuture(null));

    mockMvc
        .perform(post("/workspaces/servername:test_dash").session(session))
        .andExpect(status().isCreated());

    expectAuditEvent(
        new AuditEvent(
            instant,
            "henk",
            "SAVE_USER_WORKSPACE",
            Map.of(
                "sessionId",
                sessionId,
                "roles",
                List.of("ROLE_USER"),
                "id",
                "servername:test_dash")));
  }

  @Test
  @WithMockUser
  void testSaveWorkspaceWrongId() throws Exception {
    mockMvc
        .perform(post("/workspaces/)servername:*wrongid-dash"))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.message")
                .value(
                    "saveUserWorkspace.id: Please use only letters, numbers, dashes or underscores"));
  }

  @Test
  @WithMockUser(username = "henk")
  void testLoadWorkspace() throws Exception {
    when(commands.loadWorkspace(any(Principal.class), eq("blah")))
        .thenReturn(completedFuture(null));

    mockMvc.perform(post("/load-workspace?id=blah").session(session)).andExpect(status().isOk());

    expectAuditEvent(
        new AuditEvent(
            instant,
            "henk",
            "LOAD_USER_WORKSPACE",
            Map.of("sessionId", sessionId, "roles", List.of("ROLE_USER"), "id", "blah")));
  }

  @Test
  @WithMockUser
  void testExecute() throws Exception {
    String expression = "meanDS(D$age)";
    String rewrittenExpression = "dsBase::meanDS(D$age)";
    when(expressionRewriter.rewriteAggregate(expression)).thenReturn(rewrittenExpression);
    String serializedExpression = serializeExpression(rewrittenExpression);

    when(commands.evaluate(serializedExpression))
        .thenReturn(completedFuture(new REXPRaw(new byte[0])));

    mockMvc
        .perform(
            post("/execute")
                .session(session)
                .accept(APPLICATION_OCTET_STREAM)
                .contentType(TEXT_PLAIN)
                .content(expression))
        .andExpect(status().isOk());

    expectAuditEvent(
        new AuditEvent(
            instant,
            "user",
            "EXECUTE",
            Map.of(
                "sessionId",
                sessionId,
                "roles",
                List.of("ROLE_USER"),
                "expression",
                "meanDS(D$age)")));
  }

  @Test
  @WithMockUser
  void testExecuteAsync() throws Exception {
    when(expressionRewriter.rewriteAggregate("meanDS(D$age)")).thenReturn("dsBase::meanDS(D$age)");
    when(commands.evaluate("try(base::serialize({dsBase::meanDS(D$age)}, NULL))"))
        .thenReturn(completedFuture(new REXPDouble(36.6)));

    MvcResult result =
        mockMvc
            .perform(
                post("/execute?async=true")
                    .session(session)
                    .contentType(TEXT_PLAIN)
                    .content("meanDS(D$age)")
                    .accept(APPLICATION_OCTET_STREAM))
            .andReturn();
    mockMvc
        .perform(asyncDispatch(result))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "http://localhost/lastcommand"))
        .andExpect(content().string(""));

    expectAuditEvent(
        new AuditEvent(
            instant,
            "user",
            "EXECUTE",
            Map.of(
                "sessionId",
                sessionId,
                "roles",
                List.of("ROLE_USER"),
                "expression",
                "meanDS(D$age)")));
  }

  @Test
  @WithMockUser
  void testAssign() throws Exception {
    String expression = "meanDS(D$age)";
    String rewrittenExpression = "dsBase::meanDS(D$age)";
    when(expressionRewriter.rewriteAssign(expression)).thenReturn(rewrittenExpression);

    CompletableFuture<Void> assignment = new CompletableFuture<>();
    when(commands.assign("E", rewrittenExpression)).thenReturn(assignment);

    MvcResult result =
        mockMvc
            .perform(
                post("/symbols/E").session(session).contentType(TEXT_PLAIN).content(expression))
            .andReturn();

    assignment.complete(null);
    mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());

    expectAuditEvent(
        new AuditEvent(
            instant,
            "user",
            "ASSIGN",
            Map.of(
                "sessionId",
                sessionId,
                "roles",
                List.of("ROLE_USER"),
                "symbol",
                "E",
                "expression",
                "meanDS(D$age)")));
  }

  @Test
  @WithMockUser
  void testAssignSyntaxError() throws Exception {
    String expression = "meanDS(D$age";
    doThrow(new ExpressionException(expression, new ParseException("Missing end bracket")))
        .when(expressionRewriter)
        .rewriteAssign(expression);

    MvcResult mvcResult =
        mockMvc
            .perform(
                post("/symbols/D").session(session).contentType(TEXT_PLAIN).content(expression))
            .andExpect(status().isBadRequest())
            .andReturn();
    assertEquals(
        "Error parsing expression 'meanDS(D$age':\nMissing end bracket",
        mvcResult.getResolvedException().getMessage());

    expectAuditEvent(
        new AuditEvent(
            instant,
            "user",
            "ASSIGN_FAILURE",
            Map.of(
                "sessionId",
                sessionId,
                "roles",
                List.of("ROLE_USER"),
                "symbol",
                "D",
                "expression",
                "meanDS(D$age",
                "type",
                "ExpressionException",
                "message",
                "Error parsing expression 'meanDS(D$age':\nMissing end bracket")));
  }

  @Test
  @WithMockUser
  void testAsyncAssignExecutionFails() throws Exception {
    String expression = "meanDS(D$age)";
    String rewrittenExpression = "dsBase::meanDS(D$age)";
    when(expressionRewriter.rewriteAssign(expression)).thenReturn(rewrittenExpression);

    when(commands.assign("D", rewrittenExpression))
        .thenReturn(failedFuture(new NullPointerException("Execution failed")));

    MvcResult mvcResult =
        mockMvc
            .perform(
                post("/symbols/D").session(session).contentType(TEXT_PLAIN).content(expression))
            .andExpect(status().isOk())
            .andReturn();

    expectAuditEvent(
        new AuditEvent(
            instant,
            "user",
            "ASSIGN_FAILURE",
            Map.of(
                "sessionId",
                sessionId,
                "roles",
                List.of("ROLE_USER"),
                "symbol",
                "D",
                "expression",
                "meanDS(D$age)",
                "type",
                "java.lang.NullPointerException",
                "message",
                "Execution failed")));
  }

  @Test
  @WithMockUser
  void testExecuteSyntaxError() throws Exception {
    String expression = "meanDS(D$age";
    doThrow(new ExpressionException(expression, new ParseException("Missing end bracket")))
        .when(expressionRewriter)
        .rewriteAggregate(expression);

    MvcResult mvcResult =
        mockMvc
            .perform(
                post("/execute?async=true")
                    .session(session)
                    .accept(APPLICATION_OCTET_STREAM)
                    .contentType(TEXT_PLAIN)
                    .content(expression))
            .andExpect(status().isBadRequest())
            .andReturn();
    assertEquals(
        "Error parsing expression 'meanDS(D$age':\nMissing end bracket",
        mvcResult.getResolvedException().getMessage());

    expectAuditEvent(
        new AuditEvent(
            instant,
            "user",
            "EXECUTE_FAILURE",
            Map.of(
                "sessionId",
                sessionId,
                "roles",
                List.of("ROLE_USER"),
                "expression",
                "meanDS(D$age",
                "type",
                "ExpressionException",
                "message",
                "Error parsing expression 'meanDS(D$age':\nMissing end bracket")));
  }

  @Test
  @WithMockUser
  void testAssignAsync() throws Exception {
    String expression = "meanDS(D$age)";
    String rewrittenExpression = "dsBase::meanDS(D$age)";
    when(expressionRewriter.rewriteAssign(expression)).thenReturn(rewrittenExpression);

    final var future = new CompletableFuture<Void>();
    when(commands.assign("E", rewrittenExpression)).thenReturn(future);

    MvcResult result =
        mockMvc
            .perform(
                post("/symbols/E?async=true")
                    .session(session)
                    .contentType(TEXT_PLAIN)
                    .content("meanDS(D$age)")
                    .accept(APPLICATION_OCTET_STREAM))
            .andReturn();

    mockMvc
        .perform(asyncDispatch(result))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "http://localhost/lastcommand"))
        .andExpect(content().string(""));

    future.complete(null);

    expectAuditEvent(
        new AuditEvent(
            instant,
            "user",
            "ASSIGN",
            Map.of(
                "sessionId",
                sessionId,
                "roles",
                List.of("ROLE_USER"),
                "symbol",
                "E",
                "expression",
                "meanDS(D$age)")));
  }

  @Test
  @WithMockUser
  void testLoadTableDoesNotExist() throws Exception {
    when(armadilloStorage.tableExists("gecko", "core/core-all")).thenReturn(false);

    var result =
        mockMvc
            .perform(post("/load-table?symbol=D&table=gecko/core/core-all").session(session))
            .andReturn();
    mockMvc.perform(asyncDispatch(result)).andExpect(status().isNotFound());

    expectAuditEvent(
        new AuditEvent(
            instant,
            "user",
            "LOAD_TABLE_FAILURE",
            Map.of(
                "symbol",
                "D",
                "sessionId",
                sessionId,
                "roles",
                List.of("ROLE_USER"),
                "project",
                "gecko",
                "folder",
                "core",
                "table",
                "core-all",
                "message",
                "Table not found")));
  }

  @Test
  @WithMockUser
  void testLoadTable() throws Exception {
    when(armadilloStorage.tableExists("project", "folder/table")).thenReturn(true);
    when(commands.loadTable("D", "project/folder/table", emptyList()))
        .thenReturn(completedFuture(null));

    mockMvc
        .perform(
            post("/load-table?symbol=D&table=project/folder/table&async=false").session(session))
        .andExpect(status().isOk());

    expectAuditEvent(
        new AuditEvent(
            instant,
            "user",
            "LOAD_TABLE",
            Map.of(
                "symbol",
                "D",
                "sessionId",
                sessionId,
                "roles",
                List.of("ROLE_USER"),
                "project",
                "project",
                "folder",
                "folder",
                "table",
                "table")));
  }

  @Test
  @WithMockUser
  void testLoadTableWithVariables() throws Exception {
    when(armadilloStorage.tableExists("project", "folder/table")).thenReturn(true);
    when(commands.loadTable("D", "project/folder/table", List.of("age", "weight")))
        .thenReturn(completedFuture(null));

    mockMvc
        .perform(
            post("/load-table?symbol=D&table=project/folder/table&async=false&variables=age,weight")
                .session(session))
        .andExpect(status().isOk());

    expectAuditEvent(
        new AuditEvent(
            instant,
            "user",
            "LOAD_TABLE",
            Map.of(
                "symbol",
                "D",
                "sessionId",
                sessionId,
                "roles",
                List.of("ROLE_USER"),
                "project",
                "project",
                "folder",
                "folder",
                "table",
                "table")));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "diabetes/test/blah",
        "maximumbucketlengthincludingprefixissixtythreecharacters/test/blah",
        "000-222-211-4112/test/blah",
        "a-b-c-d/test/blah"
      })
  void testValidTableName(String name) {
    assertTrue(name.matches(TABLE_RESOURCE_REGEX));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "DIABETES/test",
        "_b/test",
        ".b/test",
        "b-/test",
        "maximumbucketlengthincludingprefixissixtythreecharactersx/test"
      })
  void testInvalidSharedWorkspaceName(String name) {
    assertFalse(name.matches(TABLE_RESOURCE_REGEX));
  }

  @Test
  @WithMockUser(roles = "SU")
  void testGetResources() throws Exception {
    when(armadilloStorage.listProjects()).thenReturn(List.of("gecko", "alspac"));
    when(armadilloStorage.listResources("gecko")).thenReturn(List.of("hpc-resource-1"));
    when(armadilloStorage.listResources("alspac")).thenReturn(List.of("hpc-resource-20"));

    mockMvc.perform(get("/resources").session(session)).andExpect(status().isOk());

    expectAuditEvent(
        new AuditEvent(
            instant,
            "user",
            "GET_RESOURCES",
            Map.of("sessionId", sessionId, "roles", List.of("ROLE_SU"))));
  }

  @Test
  @WithMockUser(roles = "SU")
  void testResourceExists() throws Exception {
    when(armadilloStorage.resourceExists("gecko", "2_1-core-1_1/hpc-resource-1")).thenReturn(true);

    mockMvc
        .perform(head("/resources/gecko/2_1-core-1_1/hpc-resource-1").session(session))
        .andExpect(status().isOk());

    expectAuditEvent(
        new AuditEvent(
            instant,
            "user",
            "RESOURCE_EXISTS",
            Map.of(
                "sessionId",
                sessionId,
                "roles",
                List.of("ROLE_SU"),
                "project",
                "gecko",
                "folder",
                "2_1-core-1_1",
                "resource",
                "hpc-resource-1")));
  }

  @Test
  @WithMockUser(roles = "SU")
  void testLoadResource() throws Exception {
    when(armadilloStorage.resourceExists("gecko", "2_1-core-1_1/hpc-resource-1")).thenReturn(true);
    when(commands.loadResource("hpc_res", "gecko/2_1-core-1_1/hpc-resource-1"))
        .thenReturn(completedFuture(null));

    mockMvc
        .perform(
            post("/load-resource?symbol=hpc_res&resource=gecko/2_1-core-1_1/hpc-resource-1")
                .session(session))
        .andExpect(status().isOk());

    expectAuditEvent(
        new AuditEvent(
            instant,
            "user",
            "LOAD_RESOURCE",
            Map.of(
                "sessionId",
                sessionId,
                "roles",
                List.of("ROLE_SU"),
                "symbol",
                "hpc_res",
                "project",
                "gecko",
                "folder",
                "2_1-core-1_1",
                "resource",
                "hpc-resource-1")));
  }

  @Test
  @WithMockUser(roles = "SU")
  void testLoadResourceFails() throws Exception {
    when(armadilloStorage.resourceExists("gecko", "2_1-core-1_1/hpc-resource-1")).thenReturn(false);
    mockMvc
        .perform(
            post("/load-resource?symbol=hpc_res&resource=gecko/2_1-core-1_1/hpc-resource-1")
                .session(session))
        .andExpect(status().isOk());

    expectAuditEvent(
        new AuditEvent(
            instant,
            "user",
            "LOAD_RESOURCE_FAILURE",
            Map.of(
                "sessionId",
                sessionId,
                "roles",
                List.of("ROLE_SU"),
                "symbol",
                "hpc_res",
                "project",
                "gecko",
                "folder",
                "2_1-core-1_1",
                "resource",
                "hpc-resource-1",
                "message",
                "Resource not found")));
  }

  @Test
  @WithMockUser(roles = "SU")
  void testGetWorkspaces() throws Exception {
    when(armadilloStorage.listWorkspaces(any(Principal.class)))
        .thenReturn(List.of(mock(Workspace.class)));

    mockMvc.perform(get("/workspaces").session(session)).andExpect(status().isOk());

    expectAuditEvent(
        new AuditEvent(
            instant,
            "user",
            "GET_USER_WORKSPACES",
            Map.of("sessionId", sessionId, "roles", List.of("ROLE_SU"))));
  }
}
