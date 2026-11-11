using System;
using System.IO;
using System.Reflection;
using System.Collections.Generic;
using UsbBridge;

// Bounded in-memory channel fixture: inspect emitted protocol credit without
// opening sockets or starting a writer thread. Feed uses production validation.
class ChannelReadTests {
 static int passed;
 static void Check(bool ok,string message){if(!ok)throw new Exception(message);passed++;Console.WriteLine("PASS "+message);}
 static Queue<byte[]> Sent(Mux mux){return (Queue<byte[]>)typeof(Mux).GetField("sends",BindingFlags.Instance|BindingFlags.NonPublic).GetValue(mux);}
 static byte[] Block(int size,int offset){byte[] data=new byte[size];for(int n=0;n<size;n++)data[n]=(byte)(n+offset);return data;}
 static void Exact(byte[] actual,int offset,int count,int start){for(int n=0;n<count;n++)if(actual[offset+n]!=(byte)(n+start))throw new Exception("Byte ordering changed at "+n);}
 static int Main(){try{
  Mux mux=new Mux(null);Channel stream=new Channel(mux,17);Queue<byte[]> sent=Sent(mux);
  stream.Feed(2,Block(4096,0));stream.Feed(2,Block(4096,4096));stream.Feed(2,Block(300,8192));byte[] target=new byte[10000];
  int count=stream.Read(target,7,5000);Exact(target,7,count,0);Check(count==5000,"a single read consumes available records across frame boundaries in order");
  byte[] credit=sent.Dequeue();Check(sent.Count==0&&credit[0]==7&&Wire.Int(credit,1)==17&&Wire.Int(credit,5)==5000,"coalesced read returns precisely the consumed credit once");
  count=stream.Read(target,0,target.Length);Exact(target,0,count,5000);Check(count==3492,"partial final record is preserved and short available read does not wait to fill buffer");
  credit=sent.Dequeue();Check(Wire.Int(credit,5)==3492,"partial queue drain returns exact remaining credit");
  Check(stream.Read(target,0,0)==0&&sent.Count==0,"zero-byte read is immediate and creates no credit");
  stream.Feed(2,Block(4096,0));stream.Feed(3,new byte[0]);count=stream.Read(target,0,target.Length);Check(count==4096&&stream.Read(target,0,target.Length)==0,"remote half-close drains available bytes before EOF");
  Mux fullMux=new Mux(null);Channel full=new Channel(fullMux,2);for(int n=0;n<16;n++)full.Feed(2,Block(4096,n*4096));byte[] large=new byte[65536];count=full.Read(large,0,large.Length);Exact(large,0,count,0);
  Check(count==65536&&Wire.Int(Sent(fullMux).Dequeue(),5)==65536,"full legacy 64 KiB receive window drains with bounded exact credit");
  for(int n=0;n<16;n++)full.Feed(2,Block(4096,n*4096));bool rejected=false;try{full.Feed(2,new byte[1]);}catch(IOException){rejected=true;}Check(rejected,"receive window overflow remains rejected");
  rejected=false;try{full.Read(new byte[4],3,2);}catch(ArgumentOutOfRangeException){rejected=true;}Check(rejected,"invalid caller buffer bounds fail before consuming queued data");
  full.Abort();rejected=false;try{full.Read(large,0,large.Length);}catch(IOException){rejected=true;}Check(rejected,"closed stream never returns stale queued traffic");
  Console.WriteLine("TOTAL "+passed+" passed");return 0;
 }catch(Exception error){Console.Error.WriteLine(error);return 1;}}
}
