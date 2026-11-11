using System;

namespace UsbBridge {
internal struct TransferRates {internal double ToPhone,FromPhone,DisplayToPhone,DisplayFromPhone;internal long Total;}
// UI timers can be delayed by a busy desktop. Use the real monotonic interval,
// and never subtract counters from a previous authenticated cable session.
internal sealed class TransferRateSampler {
 readonly long frequency;object session;long sent,received,at;double displayedSent,displayedReceived,idleSent,idleReceived;
 internal TransferRateSampler(long frequency){if(frequency<=0)throw new ArgumentOutOfRangeException("frequency");this.frequency=frequency;}
 internal void Reset(){session=null;sent=received=at=0;displayedSent=displayedReceived=idleSent=idleReceived=0;}
 // Time-based rather than frame-based smoothing stays the same if Windows
 // delays a repaint. Do not leave a decaying speed on an idle/disconnected link.
 static double Smooth(double previous,double actual,double seconds,ref double idle){
  idle=actual==0?idle+seconds:0;
  if(idle>=0.6)return 0;
  return previous+(actual-previous)*(1-Math.Exp(-seconds/0.35));
 }
 internal TransferRates Sample(object current,long currentSent,long currentReceived,long now){
  if(current==null){Reset();return new TransferRates();}
  TransferRates value=new TransferRates();value.Total=currentSent+currentReceived;
  if(!Object.ReferenceEquals(current,session)||currentSent<sent||currentReceived<received||now<at){Reset();session=current;sent=currentSent;received=currentReceived;at=now;return value;}
  if(now==at){value.DisplayToPhone=displayedSent;value.DisplayFromPhone=displayedReceived;return value;}
  double seconds=(now-at)/(double)frequency;value.ToPhone=(currentSent-sent)/seconds;value.FromPhone=(currentReceived-received)/seconds;
  value.DisplayToPhone=displayedSent=Smooth(displayedSent,value.ToPhone,seconds,ref idleSent);value.DisplayFromPhone=displayedReceived=Smooth(displayedReceived,value.FromPhone,seconds,ref idleReceived);
  sent=currentSent;received=currentReceived;at=now;return value;
 }
}
}
