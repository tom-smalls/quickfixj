package quickfix.test.acceptance;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import junit.extensions.TestSetup;
import junit.framework.AssertionFailedError;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestResult;
import junit.framework.TestSuite;

import org.apache.mina.util.AvailablePortFinder;
import org.logicalcobwebs.proxool.ProxoolException;
import org.logicalcobwebs.proxool.ProxoolFacade;
import org.logicalcobwebs.proxool.admin.SnapshotIF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import quickfix.Session;
import quickfix.mina.ProtocolFactory;

public class AcceptanceTestSuite extends TestSuite {
    private static final String ATEST_TIMEOUT_KEY = "atest.timeout";
    private static final String ATEST_TRANSPORT_KEY = "atest.transport";
    private static final String ATEST_SKIPSLOW_KEY = "atest.skipslow";
    private static final Logger log = LoggerFactory.getLogger(AcceptanceTestSuite.class);
    private static final String acceptanceTestResourcePath = "quickfix/test/acceptance/definitions/";
    private static final String acceptanceTestBaseDir;
    
    private static int transportType = ProtocolFactory.SOCKET;
    private static int port = 9887;

    private boolean skipSlowTests;
    private final boolean multithreaded;

    private  final Map<Object, Object> overridenProperties;
    
    static {
        acceptanceTestBaseDir = AcceptanceTestSuite.class.getClassLoader().
            getResource(acceptanceTestResourcePath).getPath();
    }
    
    private static class TestDefinitionFilter implements FileFilter {
        public boolean accept(File file) {
            return (file.getName().endsWith(".def") && !file.getParentFile().getName().equals(
                    "future"))
                    || file.isDirectory();
        }
    }

    private static class AcceptanceTest extends TestCase {
        private final String filename;
        private final String testname;
    
        public AcceptanceTest(String filename) {
            this.filename = filename;
            testname = filename.substring(filename.lastIndexOf(File.separatorChar + "fix") + 1);
            setName(testname);
        }
    
        public int countTestCases() {
            return 1;
        }
    
        public void run(TestResult result) {
            result.startTest(this);
            TestConnection connection = null;
            String failureString = "test " + filename + " failed with message: ";
            try {
                connection = new TestConnection();
                List<TestStep> testSteps = load(filename);
                for (int i = 0; i < testSteps.size(); i++) {
                    testSteps.get(i).run(result, connection);
                }
            } catch (AssertionFailedError e) {
                result.addFailure(this, e);
                log.error(failureString + e.getMessage());
            } catch (Throwable t) {
                result.addError(this, t);
                log.error(failureString + t.getMessage());
            } finally {
                if (connection != null) {
                    connection.tearDown();
                }
            }
            result.endTest(this);
            //printDatabasePoolingStatistics();
        }
    
        @SuppressWarnings("unused")
        protected void printDatabasePoolingStatistics() {
            String[] aliases = ProxoolFacade.getAliases();
            try {
                for (int i = 0; i < aliases.length; i++) {
                    SnapshotIF snapshot = ProxoolFacade.getSnapshot(aliases[i], true);
                    System.out.println("active:" + snapshot.getActiveConnectionCount() + ",max:"
                            + snapshot.getMaximumConnectionCount() + ",served:"
                            + snapshot.getServedCount());
                }
            } catch (ProxoolException e) {
                e.printStackTrace();
            }
        }
    
        private List<TestStep> load(String filename) throws IOException {
            ArrayList<TestStep> steps = new ArrayList<TestStep>();
            log.info("load test: " + filename);
            BufferedReader in = null;
            try {
                in = new BufferedReader(new InputStreamReader(new FileInputStream(filename), "ISO8859_1"));
                String line = in.readLine();
                while (line != null) {
                    if (line.matches("^[ \t]*#.*")) {
                        steps.add(new PrintComment(line));
                    } else if (line.startsWith("I")) {
                        steps.add(new InitiateMessageStep(line));
                    } else if (line.startsWith("E")) {
                        steps.add(new ExpectMessageStep(line));
                    } else if (line.matches("^i\\d*,?CONNECT")) {
                        steps.add(new ConnectToServerStep(line,transportType, port));
                    } else if (line.matches("^iSET_SESSION.*")) {
                        steps.add(new ConfigureSessionStep(line));
                    } else if (line.matches("^e\\d*,?DISCONNECT")) {
                        steps.add(new ExpectDisconnectStep(line));
                    }
                    line = in.readLine();
                }
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException e1) {
                        e1.printStackTrace();
                    }
                }
            }
            return steps;
        }
    
        public String toString() {
            return testname;
        }
    }

    public AcceptanceTestSuite(String testDirectory, boolean multithreaded) {
        this(testDirectory, multithreaded, null);
    }
    
    public AcceptanceTestSuite(String testDirectory, boolean multithreaded, Map<Object, Object> overridenProperties) {
        this.multithreaded = multithreaded;
        this.overridenProperties = overridenProperties;
        Long timeout = Long.getLong(ATEST_TIMEOUT_KEY);
        if (timeout != null) {
            ExpectMessageStep.TIMEOUT_IN_MS = timeout.longValue();
        }

        this.skipSlowTests = Boolean.getBoolean(ATEST_SKIPSLOW_KEY);

        addTests(new File(acceptanceTestBaseDir + testDirectory+ "/fix40"));
        addTests(new File(acceptanceTestBaseDir + testDirectory+ "/fix41"));
        addTests(new File(acceptanceTestBaseDir + testDirectory+ "/fix42"));
        addTests(new File(acceptanceTestBaseDir + testDirectory+ "/fix43"));
        addTests(new File(acceptanceTestBaseDir + testDirectory+ "/fix44"));
        addTests(new File(acceptanceTestBaseDir + testDirectory+ "/fix50"));
    }

    public String toString() {
        return "FIX Integration Tests: " + (multithreaded ? "multithreaded" : "single threaded");
    }
    
    public boolean isMultithreaded()
    {
        return multithreaded;
    }
    
    protected void addTest(String name) {
        addTests(new File(acceptanceTestBaseDir + "server/" + name));
    }

    protected void addTests(File directory) {
        if (!directory.exists()) return;
        if (!directory.isDirectory()) {
            addTest(new AcceptanceTest(directory.getPath()));
        } else {
            if (directory.exists()) {
                File[] files = directory.listFiles(new TestDefinitionFilter());
                for (int i = 0; i < files.length; i++) {
                    if (!files[i].isDirectory() && !isTestSkipped(files[i])) {
                        addTest(new AcceptanceTest(files[i].getPath()));
                    }
                }
                for (int i = 0; i < files.length; i++) {
                    if (files[i].isDirectory()) {
                        addTests(files[i]);
                    }
                }
            } else {
                System.err.println("directory does not exist: " + directory.getPath());
            }
        }
    }

    private boolean isTestSkipped(File file) {
        if (skipSlowTests) {
            return file.getName().indexOf("NoDataSentDuringHeartBtInt") != -1
                    || file.getName().indexOf("SendTestRequest") != -1;
        } else {
            return false;
        }
    }

    private static final class AcceptanceTestServerSetUp extends TestSetup {
        private boolean threaded;
        private Map<Object, Object> overridenProperties;
//        private Thread serverThread;
        private final ExecutorService executor = Executors.newSingleThreadExecutor();

        private ATServer server;

        private AcceptanceTestServerSetUp(AcceptanceTestSuite suite) {
            super(suite);
            this.threaded = suite.isMultithreaded();
            this.overridenProperties = suite.getOverridenProperties();
        }

        protected void setUp() throws Exception {
            super.setUp();
            server = new ATServer((TestSuite) getTest(), threaded, transportType, port,  overridenProperties);
            server.setUsingMemoryStore(true);
            executor.execute(server);
            server.waitForInitialization();
        }

        protected void tearDown() throws Exception {
            server.stop();
            executor.shutdownNow();
            server.waitForTearDown();
            super.tearDown();
        }
    }

    public static Test suite() {
        transportType = ProtocolFactory.getTransportType(System.getProperty(ATEST_TRANSPORT_KEY, ProtocolFactory.getTypeString(ProtocolFactory.SOCKET)));
        port = AvailablePortFinder.getNextAvailable(port);
        TestSuite acceptanceTests = new TestSuite();
        //default server
        acceptanceTests.addTest(new AcceptanceTestServerSetUp(new AcceptanceTestSuite("server", false)));
        acceptanceTests.addTest(new AcceptanceTestServerSetUp(new AcceptanceTestSuite("server", true)));
        
        Map<Object, Object> resendRequestChunkSizeProperties = new HashMap<Object, Object>();
        resendRequestChunkSizeProperties.put(Session.SETTING_RESEND_REQUEST_CHUNK_SIZE, "5");
        acceptanceTests.addTest(new AcceptanceTestServerSetUp(new AcceptanceTestSuite("resendRequestChunkSize", true,  resendRequestChunkSizeProperties )));
        acceptanceTests.addTest(new AcceptanceTestServerSetUp(new AcceptanceTestSuite("resendRequestChunkSize", false,resendRequestChunkSizeProperties )));
    
        Map<Object, Object> lastMsgSeqNumProcessedProperties = new HashMap<Object, Object>();
        lastMsgSeqNumProcessedProperties.put(Session.SETTING_ENABLE_LAST_MSG_SEQ_NUM_PROCESSED, "Y");
        acceptanceTests.addTest(new AcceptanceTestServerSetUp(new AcceptanceTestSuite("lastMsgSeqNumProcessed", true,  lastMsgSeqNumProcessedProperties )));
        acceptanceTests.addTest(new AcceptanceTestServerSetUp(new AcceptanceTestSuite("lastMsgSeqNumProcessed", false,lastMsgSeqNumProcessedProperties )));
        
        Map<Object, Object> nextExpectedMsgSeqNumProperties = new HashMap<Object, Object>();
        nextExpectedMsgSeqNumProperties.put(Session.SETTING_ENABLE_NEXT_EXPECTED_MSG_SEQ_NUM, "Y");
        acceptanceTests.addTest(new AcceptanceTestServerSetUp(new AcceptanceTestSuite("nextExpectedMsgSeqNum", true,  nextExpectedMsgSeqNumProperties )));
        acceptanceTests.addTest(new AcceptanceTestServerSetUp(new AcceptanceTestSuite("nextExpectedMsgSeqNum", false, nextExpectedMsgSeqNumProperties )));
        
        return acceptanceTests;
    }

    public Map<Object, Object> getOverridenProperties() {
        return overridenProperties;
    }
}
