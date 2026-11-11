using System;
using System.Runtime.InteropServices;
using System.Threading;

namespace UsbBridge {
// The name is independent of the EXE path/version. Opening another extracted
// copy must not open the same MIDI ports or start another phone handshake.
public sealed class SingleInstance:IDisposable {
 public static readonly int ActivateMessage=(int)RegisterWindowMessage("USBBridge.Desktop.Activate.1");
 readonly Mutex mutex;bool owns;
 [DllImport("user32.dll",CharSet=CharSet.Unicode)]static extern uint RegisterWindowMessage(string name);
 [DllImport("user32.dll")]static extern bool PostMessage(IntPtr window,int message,IntPtr wParam,IntPtr lParam);
 public SingleInstance(string name){mutex=new Mutex(false,name);try{owns=mutex.WaitOne(0,false);}catch(AbandonedMutexException){owns=true;}}
 public bool IsOwner{get{return owns;}}
 public static void ActivateExisting(){PostMessage(new IntPtr(0xffff),ActivateMessage,IntPtr.Zero,IntPtr.Zero);}
 public void Dispose(){if(owns){owns=false;mutex.ReleaseMutex();}mutex.Close();}
}
}
