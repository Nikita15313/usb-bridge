using System;
using System.Collections.Generic;

namespace UsbBridge {
// Transport EOF is not proof of physical removal: the phone may be restarting
// its service or changing USB modes. Stop only after device enumeration is also
// empty for a short grace period, retaining automatic recovery for a live cable.
internal sealed class CablePresence {
 readonly Adapter adapter;readonly string input,output;bool missing;int missingAt;
 internal CablePresence(Adapter adapter,string input,string output){this.adapter=adapter;this.input=Name(input);this.output=Name(output);}
 static string Name(string value){if(String.IsNullOrEmpty(value))return "";int separator=value.IndexOf('·');return (separator<0?value:value.Substring(separator+1)).Trim();}
 static bool Contains(string[] ports,string wanted){if(wanted.Length==0)return false;foreach(string port in ports)if(String.Equals(Name(port),wanted,StringComparison.OrdinalIgnoreCase))return true;return false;}
 static bool PhoneMidi(string name){string lower=Name(name).ToLowerInvariant();return lower.Contains("midi function")||lower.Contains("android")||lower.Contains("oneplus");}
 internal bool Present(List<Adapter> networks,string[] inputs,string[] outputs){
  foreach(Adapter network in networks)if(network.UsbPhone||NetworkCatalog.SameInterface(adapter,network))return true;
  if(Contains(inputs,input)&&Contains(outputs,output))return true;
  // USB-network -> MIDI mode switching changes the Windows device identity.
  foreach(string candidate in inputs)if(PhoneMidi(candidate)&&Contains(outputs,Name(candidate)))return true;
  return false;
 }
 internal bool Removed(bool present,int ticks){if(present){missing=false;return false;}if(!missing){missing=true;missingAt=ticks;return false;}return unchecked(ticks-missingAt)>=1200;}
}
}
