using System;
using UsbBridge;

class TransferRateTests {
 static int passed;
 static void Check(bool value,string message){if(!value)throw new Exception(message);passed++;Console.WriteLine("PASS "+message);}
 static bool Near(double actual,double expected){return Math.Abs(actual-expected)<0.001;}
 static int Main(){try{
  TransferRateSampler sampler=new TransferRateSampler(1000);object first=new object(),next=new object();
  TransferRates r=sampler.Sample(first,0,0,100);Check(r.ToPhone==0&&r.FromPhone==0,"new session starts with zero speed");
  r=sampler.Sample(first,1024,2048,1100);Check(Near(r.ToPhone,1024)&&Near(r.FromPhone,2048)&&r.Total==3072,"send and receive rates are separate actual counter deltas");
  r=sampler.Sample(first,2048,2560,1350);Check(Near(r.ToPhone,4096)&&Near(r.FromPhone,2048),"short timer interval uses actual elapsed time");
  r=sampler.Sample(first,5120,4096,2850);Check(Near(r.ToPhone,2048)&&Near(r.FromPhone,1024),"delayed UI timer does not inflate the rate");
  r=sampler.Sample(first,6144,5120,2850);Check(r.ToPhone==0&&r.FromPhone==0,"zero elapsed interval never divides by zero");
  r=sampler.Sample(first,7168,6144,3850);Check(Near(r.ToPhone,2048)&&Near(r.FromPhone,2048),"same-timestamp sample does not lose pending byte deltas");
  r=sampler.Sample(next,25,50,3900);Check(r.ToPhone==0&&r.FromPhone==0&&r.Total==75,"replacement cable never inherits previous session throughput");
  r=sampler.Sample(next,5,10,4000);Check(r.ToPhone==0&&r.FromPhone==0,"reset transport counters cannot produce negative rates");
  r=sampler.Sample(null,0,0,4100);Check(r.Total==0&&r.ToPhone==0&&r.FromPhone==0,"disconnect clears every displayed counter");
  r=sampler.Sample(next,100000,200000,4500);Check(r.ToPhone==0&&r.FromPhone==0,"reconnect starts a new rate baseline even with same object");
  r=sampler.Sample(next,101000,201000,4400);Check(r.ToPhone==0&&r.FromPhone==0,"backward timer input resets the sample safely");
  sampler.Reset();r=sampler.Sample(first,5000000000L,7000000000L,0);r=sampler.Sample(first,5000004096L,7000008192L,1000);Check(Near(r.ToPhone,4096)&&Near(r.FromPhone,8192)&&r.Total==12000012288L,"long-running transfer counters retain 64-bit precision");
  sampler.Reset();sampler.Sample(first,0,0,0);r=sampler.Sample(first,2000,4000,200);
  Check(r.DisplayToPhone>4000&&r.DisplayToPhone<5000&&Near(r.DisplayFromPhone,r.DisplayToPhone*2),"200 ms display responds promptly with independent smoothed directions");
  double displayed=r.DisplayToPhone;r=sampler.Sample(first,2000,4000,200);Check(Near(r.DisplayToPhone,displayed),"duplicate timer tick retains display without introducing a false zero");
  r=sampler.Sample(first,2000,6000,400);Check(r.ToPhone==0&&r.DisplayToPhone>0&&r.DisplayToPhone<displayed,"brief traffic gaps ease toward zero while raw rate remains exact");
  r=sampler.Sample(first,2000,8000,600);r=sampler.Sample(first,2000,10000,800);Check(r.DisplayToPhone==0&&r.DisplayFromPhone>0,"idle direction reaches exact zero within 600 ms independently of busy direction");
  r=sampler.Sample(null,0,0,900);Check(r.DisplayToPhone==0&&r.DisplayFromPhone==0,"disconnect clears smoothed rates immediately");
  TransferRateSampler fast=new TransferRateSampler(1000),slow=new TransferRateSampler(1000);fast.Sample(first,0,0,0);slow.Sample(first,0,0,0);TransferRates a=new TransferRates(),b=new TransferRates();
  for(int time=100;time<=1000;time+=100)a=fast.Sample(first,time*10,time*20,time);
  for(int time=200;time<=1000;time+=200)b=slow.Sample(first,time*10,time*20,time);
  Check(Near(a.DisplayToPhone,b.DisplayToPhone)&&Near(a.DisplayFromPhone,b.DisplayFromPhone),"smoothing is elapsed-time based and independent of timer frequency");
  r=slow.Sample(next,10,20,1100);Check(r.DisplayToPhone==0&&r.DisplayFromPhone==0,"replacement session never inherits previous smoothed speed");
  r=fast.Sample(first,10000,20000,3000);Check(r.ToPhone==0&&r.FromPhone==0&&r.DisplayToPhone==0&&r.DisplayFromPhone==0,"delayed idle repaint clears rates without a long animation tail");
  Console.WriteLine("TOTAL "+passed+" passed");return 0;
 }catch(Exception error){Console.Error.WriteLine(error);return 1;}}
}
