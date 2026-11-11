package local.usbbridge;

/** Monotonic, session-scoped byte deltas. Sampling never creates network traffic. */
final class TrafficRate {
 private Object session;
 private long at,sent,received;
 private long lastUp,lastDown;
 private double filteredUp,filteredDown;
 long up,down,total;
 long smoothUp,smoothDown;
 void reset(){session=null;at=sent=received=up=down=total=lastUp=lastDown=smoothUp=smoothDown=0;filteredUp=filteredDown=0;}
 void sample(Object current,long now,long tx,long rx){
  if(current==null){reset();return;}
  total=Math.max(0,tx)+Math.max(0,rx);
  if(session==current&&now==at&&tx>=sent&&rx>=received)return;
  if(session!=current||now<at||tx<sent||rx<received){session=current;up=down=smoothUp=smoothDown=0;filteredUp=filteredDown=0;lastUp=lastDown=now;}
  else{
   long elapsed=now-at;up=Math.max(0,Math.round((tx-sent)*1000.0/elapsed));down=Math.max(0,Math.round((rx-received)*1000.0/elapsed));
   double blend=1-Math.exp(-elapsed/350.0);
   if(tx>sent)lastUp=now;if(rx>received)lastDown=now;
   filteredUp=now-lastUp>=600?0:filteredUp+blend*(up-filteredUp);
   filteredDown=now-lastDown>=600?0:filteredDown+blend*(down-filteredDown);
   smoothUp=Math.max(0,Math.round(filteredUp));smoothDown=Math.max(0,Math.round(filteredDown));
  }
  at=now;sent=tx;received=rx;
 }
}
