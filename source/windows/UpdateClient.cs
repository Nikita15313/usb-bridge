using System;
using System.IO;
using System.Threading;

namespace UsbBridge {
internal delegate void UpdateNotice(UpdateRelease release,string error);
internal delegate void UpdateReady(string folder,Exception error);
internal sealed class UpdateClient:IDisposable {
 readonly Timer timer;readonly Random random=new Random();readonly UpdateNotice notify;
 readonly object gate=new object();bool disposed,checking,downloading;int failures;string etag="";long sequence;UpdateRelease cached;
 internal UpdateClient(UpdateNotice callback){notify=callback;timer=new Timer(Check,null,3000,Timeout.Infinite);}
 internal void CheckNow(){lock(gate){if(disposed||checking||downloading)return;timer.Change(0,Timeout.Infinite);}}
 internal static int NextDelay(int failures,int jitter){int seconds=failures==0?30:Math.Min(300,30*(1<<Math.Min(failures,4)));return seconds*1000+Math.Max(0,Math.Min(jitter,3000));}
 internal static bool ShouldOffer(UpdateRelease release){return release!=null&&release.Code>UpdateManifest.CurrentCode&&new Version(release.Name).CompareTo(new Version(UpdateManifest.CurrentName))>0;}
 void Check(object unused){lock(gate){if(disposed||checking||downloading)return;checking=true;}try{UpdateHttpResult response=UpdateHttp.GetManifest(UpdateManifest.ManifestUrl,etag);UpdateRelease offered=cached;if(response.Status!=304){offered=UpdateManifest.Read(response.Bytes);if(offered!=null&&(offered.Sequence<sequence||(cached!=null&&offered.Code<cached.Code)))throw new InvalidDataException("Older release manifest ignored");cached=offered;etag=response.ETag;if(offered!=null)sequence=offered.Sequence;}lock(gate){if(disposed)return;failures=0;}notify(ShouldOffer(offered)?offered:null,"");}catch(Exception error){lock(gate){failures=Math.Min(6,failures+1);if(disposed)return;}DiagnosticLog.Write("Update check deferred: "+error.Message);notify(ShouldOffer(cached)?cached:null,"Не удалось проверить обновления. Повторим автоматически.");}finally{lock(gate){checking=false;if(!disposed)timer.Change(NextDelay(failures,random.Next(0,3001)),Timeout.Infinite);}}}
 internal bool Download(UpdateRelease release,UpdateReady completed){lock(gate){if(disposed||checking||downloading||release==null||release.Code<=UpdateManifest.CurrentCode)return false;downloading=true;timer.Change(Timeout.Infinite,Timeout.Infinite);}Thread worker=new Thread(delegate(){string partial=null;try{string root=UpdateInstaller.Root;Directory.CreateDirectory(root);partial=Path.Combine(root,"download-"+Guid.NewGuid().ToString("N")+".zip.part");UpdateHttp.GetAsset(release,partial);string folder=UpdateInstaller.Prepare(root,release,partial);lock(gate){if(disposed)return;}completed(folder,null);}catch(Exception error){lock(gate){if(disposed)return;}completed(null,error);}finally{if(partial!=null)try{File.Delete(partial);}catch{}lock(gate){downloading=false;if(!disposed)timer.Change(30000,Timeout.Infinite);}}});worker.IsBackground=true;worker.Name="USBBridge update download";worker.Start();return true;}
 public void Dispose(){lock(gate){if(disposed)return;disposed=true;timer.Dispose();}}
}
}
