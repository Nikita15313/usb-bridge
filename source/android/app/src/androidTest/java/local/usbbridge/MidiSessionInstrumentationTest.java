package local.usbbridge;

import android.test.InstrumentationTestCase;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;

/** Genuine SysEx/CRC/sequence processing; no physical USB or network is needed. */
@SuppressWarnings("deprecation")
public final class MidiSessionInstrumentationTest extends InstrumentationTestCase {
 private MidiLink midi;
 private final BlockingQueue<MidiLink.Pipe> opened=new LinkedBlockingQueue<>();
 private final List<byte[]> sent=new CopyOnWriteArrayList<>();
 private ExecutorService readers;
 @Override protected void setUp(){
  readers=Executors.newCachedThreadPool();
  midi=new MidiLink(new MidiLink.Listener(){public void opened(MidiLink.Pipe p){opened.add(p);}public void status(String s){}},b->sent.add(Arrays.copyOf(b,b.length)));
 }
 @Override protected void tearDown(){midi.close();readers.shutdownNow();}
 private byte[] packet(int type,int session,int seq,byte[] data){
  byte[] raw=new byte[data.length+13];raw[0]=(byte)type;Wire.put(raw,1,session);Wire.put(raw,5,seq);System.arraycopy(data,0,raw,9,data.length);Wire.put(raw,raw.length-4,(int)MidiLink.crc(raw,raw.length-4));return MidiLink.encode(raw);
 }
 private void hello(int session){midi.feed(packet(2,session,0,new byte[0]));}
 private void data(int session,int seq,byte... data){midi.feed(packet(0,session,seq,data));}
 private MidiLink.Pipe next()throws Exception{MidiLink.Pipe pipe=opened.poll(2,TimeUnit.SECONDS);assertNotNull("MIDI stream opened",pipe);return pipe;}
 private byte[] read(MidiLink.Pipe pipe,int size)throws Exception{return readers.submit(()->Wire.read(pipe.in,size)).get(2,TimeUnit.SECONDS);}

 public void testSecondPcHelloCannotDisconnectActiveStream()throws Exception{
  hello(101);MidiLink.Pipe first=next();data(101,1,(byte)7,(byte)8);
  int replies=sent.size();for(int i=0;i<10;i++){hello(202);data(202,1,(byte)99);}
  assertSame(first,midi.current);assertFalse(first.closed);assertTrue(opened.isEmpty());
  assertEquals("Competing host never receives a false HELLO acknowledgement",replies,sent.size());
  data(101,2,(byte)9);assertTrue(Arrays.equals(new byte[]{7,8,9},read(first,3)));
 }
 public void testRepeatedHelloAndRetransmissionDoNotResetSequenceOrDuplicateData()throws Exception{
  hello(101);MidiLink.Pipe first=next();data(101,1,(byte)3);hello(101);hello(101);data(101,1,(byte)3);data(101,2,(byte)4);
  assertSame(first,midi.current);assertTrue(opened.isEmpty());assertEquals(3,first.expected);
  assertTrue(Arrays.equals(new byte[]{3,4},read(first,2)));synchronized(first){assertTrue(first.queue.isEmpty());}
 }
 public void testNewSessionMayConnectAfterPreviousStreamActuallyCloses()throws Exception{
  hello(101);MidiLink.Pipe first=next();first.close();hello(202);MidiLink.Pipe second=next();
  assertNotSame(first,second);assertEquals(202,second.session);hello(101);assertSame(second,midi.current);
  data(101,1,(byte)99);data(202,1,(byte)42);assertTrue(Arrays.equals(new byte[]{42},read(second,1)));
 }
 public void testMalformedHelloCannotReplaceCurrentSession()throws Exception{
  hello(101);MidiLink.Pipe first=next();byte[] corrupt=packet(2,202,0,new byte[0]);corrupt[corrupt.length-3]^=1;midi.feed(corrupt);
  assertSame(first,midi.current);assertFalse(first.closed);assertTrue(opened.isEmpty());
 }
 public void testRemoteCloseReleasesReadWithoutEchoAndAllowsNewSession()throws Exception{
  hello(101);MidiLink.Pipe first=next();int replies=sent.size();
  Future<Integer> read=readers.submit(()->first.in.read());midi.feed(packet(4,101,0,new byte[0]));
  try{read.get(2,TimeUnit.SECONDS);fail("Closed stream must wake its reader");}catch(ExecutionException expected){assertTrue(expected.getCause() instanceof java.io.IOException);}
  assertTrue(first.closed);assertEquals("Remote CLOSE must not echo",replies,sent.size());
  hello(202);MidiLink.Pipe second=next();assertNotSame(first,second);assertFalse(second.closed);
  midi.feed(packet(4,101,0,new byte[0]));assertFalse("Late old CLOSE cannot kill a replacement",second.closed);
  data(202,1,(byte)73);assertTrue(Arrays.equals(new byte[]{73},read(second,1)));
 }
 public void testGracefulCloseSendsExactlyOneScopedCloseFrame()throws Exception{
  hello(101);MidiLink.Pipe first=next();int replies=sent.size();first.close();first.close();
  assertEquals(replies+1,sent.size());assertTrue(Arrays.equals(packet(4,101,0,new byte[0]),sent.get(replies)));
 }
 public void testInvalidCloseIsIgnored()throws Exception{
  hello(101);MidiLink.Pipe first=next();midi.feed(packet(4,202,0,new byte[0]));midi.feed(packet(4,101,1,new byte[0]));midi.feed(packet(4,101,0,new byte[]{1}));
  byte[] corrupt=packet(4,101,0,new byte[0]);corrupt[corrupt.length-3]^=1;midi.feed(corrupt);assertFalse(first.closed);
 }
}
