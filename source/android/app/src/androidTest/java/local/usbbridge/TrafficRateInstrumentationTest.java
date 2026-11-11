package local.usbbridge;
import android.test.InstrumentationTestCase;
public final class TrafficRateInstrumentationTest extends InstrumentationTestCase {
 public void testFastSmoothingAndIdleReset(){TrafficRate r=new TrafficRate();Object link=new Object();r.sample(link,1000,0,0);r.sample(link,1200,2000,4000);assertEquals(10000L,r.up);assertTrue(r.smoothUp>0&&r.smoothUp<r.up);r.sample(link,1800,2000,4000);assertEquals(0L,r.smoothUp);assertEquals(0L,r.smoothDown);r.sample(link,2000,4000,8000);r.sample(null,2001,0,0);assertEquals(0L,r.smoothDown);}
 public void testUsesActualElapsedTimeAndSeparateDirections(){TrafficRate r=new TrafficRate();Object link=new Object();r.sample(link,1000,100,200);r.sample(link,1500,600,1200);assertEquals(1000L,r.up);assertEquals(2000L,r.down);assertEquals(1800L,r.total);r.sample(link,3500,1600,5200);assertEquals(500L,r.up);assertEquals(2000L,r.down);}
 public void testDisconnectImmediatelyClearsOldRate(){TrafficRate r=new TrafficRate();Object link=new Object();r.sample(link,1000,0,0);r.sample(link,2000,1000,2000);r.sample(null,2001,0,0);assertEquals(0L,r.up);assertEquals(0L,r.down);assertEquals(0L,r.total);}
 public void testNewSessionCannotReuseOldDeltas(){TrafficRate r=new TrafficRate();r.sample(new Object(),1000,900,800);r.sample(new Object(),2000,5000,4000);assertEquals(0L,r.up);assertEquals(0L,r.down);assertEquals(9000L,r.total);}
 public void testIdleIntervalShowsZero(){TrafficRate r=new TrafficRate();Object link=new Object();r.sample(link,1000,1,2);r.sample(link,2000,1,2);assertEquals(0L,r.up);assertEquals(0L,r.down);}
}
