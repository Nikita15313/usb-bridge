using System;
using System.IO;
using System.Text;

namespace UsbBridge {
public static class DiagnosticLog {
 static readonly object gate=new object();
 public static string PathName{get{return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"USBBridge\\USBBridge.log");}}
 public static void Write(string message){try{lock(gate){string path=PathName;Directory.CreateDirectory(Path.GetDirectoryName(path));
  if(File.Exists(path)&&new FileInfo(path).Length>524288){string previous=path+".previous";if(File.Exists(previous))File.Delete(previous);File.Move(path,previous);}
  File.AppendAllText(path,DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss.fff")+" "+message+Environment.NewLine,Encoding.UTF8);
 }}catch{/* Diagnostics must never interrupt the cable. */}}
}
}
