package local.usbbridge;

import android.test.InstrumentationTestCase;

/** Permission and IPC failures must not masquerade as a stopped helper. */
@SuppressWarnings("deprecation")
public final class HelperAccessInstrumentationTest extends InstrumentationTestCase {
 private static final class Probe implements HelperAccess.Probe {
  boolean alive=true,allowed=true,dieOnQuery;int uid=2000,queries;String denied="";RuntimeException failure;
  public boolean alive(){return alive;}
  public boolean allowed(){return allowed;}
  public int uid(){if(!allowed)throw new AssertionError("Do not query the server before app consent");return uid;}
  public boolean permission(String name){queries++;if(failure!=null){RuntimeException error=failure;failure=null;if(dieOnQuery)alive=false;throw error;}return !name.equals(denied);}
 }
 public void testMissingInstallationDoesNotQueryBinder(){Probe probe=new Probe();assertEquals(HelperAccess.MISSING,HelperAccess.inspect(false,probe).state);assertEquals(0,probe.queries);}
 public void testAbsentClientBinderDoesNotQueryPermissions(){Probe probe=new Probe();probe.alive=false;assertEquals(HelperAccess.UNAVAILABLE,HelperAccess.inspect(true,probe).state);assertEquals(0,probe.queries);}
 public void testConsentIsCheckedBeforeServerPermissions(){Probe probe=new Probe();probe.allowed=false;assertEquals(HelperAccess.PERMISSION,HelperAccess.inspect(true,probe).state);assertEquals(0,probe.queries);}
 public void testAllRequiredPermissionsAreQueried(){Probe probe=new Probe();assertEquals(HelperAccess.READY,HelperAccess.inspect(true,probe).state);assertEquals(3,probe.queries);}
 public void testEachMissingShellPermissionIsReported(){for(String name:HelperAccess.PERMISSIONS){Probe probe=new Probe();probe.denied=name;HelperAccess.Result result=HelperAccess.inspect(true,probe);assertEquals(HelperAccess.LIMITED,result.state);assertTrue(result.detail.contains(name));}}
 public void testUnexpectedIdentityDoesNotGrantRouting(){Probe probe=new Probe();probe.uid=10123;assertEquals(HelperAccess.LIMITED,HelperAccess.inspect(true,probe).state);assertEquals(0,probe.queries);}
 public void testLiveServerQueryFailureCanRecoverWithoutRestart(){Probe probe=new Probe();probe.failure=new IllegalStateException("fixture IPC failure");HelperAccess.Result result=HelperAccess.inspect(true,probe);assertEquals(HelperAccess.ERROR,result.state);assertTrue(result.detail.contains("fixture IPC failure"));assertEquals(HelperAccess.READY,HelperAccess.inspect(true,probe).state);}
 public void testPermissionRevocationDoesNotReportServerStopped(){Probe probe=new Probe();probe.failure=new SecurityException("fixture consent revoked");assertEquals(HelperAccess.PERMISSION,HelperAccess.inspect(true,probe).state);}
 public void testBinderDeathDuringQueryReportsUnavailability(){Probe probe=new Probe();probe.failure=new IllegalStateException("fixture dead binder");probe.dieOnQuery=true;HelperAccess.Result result=HelperAccess.inspect(true,probe);assertEquals(HelperAccess.UNAVAILABLE,result.state);assertTrue(result.detail.contains("fixture dead binder"));}
}
