package local.usbbridge;
import android.test.InstrumentationTestCase;

@SuppressWarnings("deprecation")
public final class InternetHealthTest extends InstrumentationTestCase {
 public void testFreshSessionRequiresAnActualSuccess(){InternetHealth h=new InternetHealth();h.failure();assertFalse(h.proven());assertEquals(0L,h.lastSuccess());}
 public void testTransientWebsiteFailureKeepsEarlierEvidence(){InternetHealth h=new InternetHealth();h.success(1000);h.failure();assertTrue(h.proven());assertFalse(h.repeatedFailure());assertEquals(1000L,h.lastSuccess());}
 public void testRepeatedFailureIsNotCableLoss(){InternetHealth h=new InternetHealth();h.success(1000);for(int i=0;i<3;i++)h.failure();assertTrue(h.repeatedFailure());assertTrue(h.proven());assertEquals(1000L,h.lastSuccess());}
 public void testRecoveryClearsFailureCount(){InternetHealth h=new InternetHealth();for(int i=0;i<5;i++)h.failure();h.success(9000);assertEquals(0,h.failures());assertFalse(h.repeatedFailure());assertEquals(9000L,h.lastSuccess());}
 public void testNewCableCannotReusePreviousProof(){InternetHealth h=new InternetHealth();h.success(1000);h.failure();h.reset();assertFalse(h.proven());assertEquals(0,h.failures());assertEquals(0L,h.lastSuccess());}
}
