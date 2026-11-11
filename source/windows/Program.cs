using System;
using System.IO;
using System.Text;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Net;
using System.Net.Sockets;
using System.Collections.Generic;
using System.Threading;
using System.Windows.Forms;
using System.Diagnostics;

namespace UsbBridge {
static class UiShape {
 public static GraphicsPath Rounded(Rectangle bounds,int radius){GraphicsPath path=new GraphicsPath();int size=Math.Max(2,radius*2);Rectangle arc=new Rectangle(bounds.X,bounds.Y,size,size);path.AddArc(arc,180,90);arc.X=bounds.Right-size;path.AddArc(arc,270,90);arc.Y=bounds.Bottom-size;path.AddArc(arc,0,90);arc.X=bounds.X;path.AddArc(arc,90,90);path.CloseFigure();return path;}
}
public sealed class CardPanel:UserControl {
 public Color Fill=Color.White;
 public CardPanel(){SetStyle(ControlStyles.AllPaintingInWmPaint|ControlStyles.OptimizedDoubleBuffer|ControlStyles.UserPaint|ControlStyles.ResizeRedraw,true);BackColor=Color.FromArgb(244,247,242);}
 protected override void OnPaintBackground(PaintEventArgs e){Color behind=Parent==null?SystemColors.Control:Parent.BackColor;using(SolidBrush b=new SolidBrush(behind))e.Graphics.FillRectangle(b,ClientRectangle);}
 protected override void OnPaint(PaintEventArgs e){e.Graphics.SmoothingMode=SmoothingMode.AntiAlias;Rectangle r=new Rectangle(0,0,Math.Max(1,Width-1),Math.Max(1,Height-1));using(GraphicsPath p=UiShape.Rounded(r,18))using(SolidBrush b=new SolidBrush(Fill))e.Graphics.FillPath(b,p);}
}
public sealed class ModernButton:Control {
 public bool Primary;bool hover;
 readonly Color green=Color.FromArgb(20,112,91),pale=Color.FromArgb(235,246,240),line=Color.FromArgb(210,228,219);
 public ModernButton(bool primary){Primary=primary;Cursor=Cursors.Hand;TabStop=true;AccessibleRole=AccessibleRole.PushButton;SetStyle(ControlStyles.AllPaintingInWmPaint|ControlStyles.OptimizedDoubleBuffer|ControlStyles.UserPaint|ControlStyles.Selectable|ControlStyles.ResizeRedraw,true);}
 protected override void OnTextChanged(EventArgs e){base.OnTextChanged(e);Invalidate();}
 protected override void OnEnabledChanged(EventArgs e){base.OnEnabledChanged(e);Invalidate();}
 protected override void OnGotFocus(EventArgs e){base.OnGotFocus(e);Invalidate();}
 protected override void OnLostFocus(EventArgs e){base.OnLostFocus(e);Invalidate();}
 protected override void OnMouseDown(MouseEventArgs e){Focus();base.OnMouseDown(e);}
 protected override void OnKeyDown(KeyEventArgs e){if(e.KeyCode==Keys.Space||e.KeyCode==Keys.Enter){OnClick(EventArgs.Empty);e.Handled=true;}base.OnKeyDown(e);}
 protected override void OnMouseEnter(EventArgs e){hover=true;Invalidate();base.OnMouseEnter(e);}protected override void OnMouseLeave(EventArgs e){hover=false;Invalidate();base.OnMouseLeave(e);}
 protected override void OnPaint(PaintEventArgs e){e.Graphics.SmoothingMode=SmoothingMode.AntiAlias;Color backdrop=Parent is CardPanel?((CardPanel)Parent).Fill:Parent==null?SystemColors.Control:Parent.BackColor;using(SolidBrush background=new SolidBrush(backdrop))e.Graphics.FillRectangle(background,ClientRectangle);Color fill=Primary?(hover?Color.FromArgb(14,91,74):green):(hover?Color.FromArgb(225,241,233):pale);if(!Enabled)fill=Color.FromArgb(232,235,232);Rectangle r=new Rectangle(0,0,Math.Max(1,Width-1),Math.Max(1,Height-1));using(GraphicsPath p=UiShape.Rounded(r,11)){using(SolidBrush b=new SolidBrush(fill))e.Graphics.FillPath(b,p);if(!Primary)using(Pen pen=new Pen(line))e.Graphics.DrawPath(pen,p);if(Focused)using(Pen pen=new Pen(Primary?Color.FromArgb(218,244,230):green,1))using(GraphicsPath focus=UiShape.Rounded(new Rectangle(3,3,Math.Max(1,Width-7),Math.Max(1,Height-7)),8))e.Graphics.DrawPath(pen,focus);}Color text=Enabled?(Primary?Color.White:green):Color.FromArgb(135,143,139);TextRenderer.DrawText(e.Graphics,Text,Font,ClientRectangle,text,TextFormatFlags.HorizontalCenter|TextFormatFlags.VerticalCenter|TextFormatFlags.EndEllipsis);}
}

// Frequent counter updates repaint only their own opaque, buffered rectangle.
// Transparent labels can ask the rounded parent to repaint on every update.
internal sealed class TransferLabel:Label {
 public TransferLabel(){SetStyle(ControlStyles.AllPaintingInWmPaint|ControlStyles.OptimizedDoubleBuffer|ControlStyles.UserPaint|ControlStyles.ResizeRedraw,true);BackColor=Color.White;}
}

public sealed class MainForm:Form {
 readonly Color green=Color.FromArgb(20,112,91),ink=Color.FromArgb(22,43,39),muted=Color.FromArgb(83,103,98),page=Color.FromArgb(244,247,242);
 ComboBox adapters;Label status,detail,stats,source,wifiState,updateText;ModernButton connect,updateButton;UpdateClient updates;UpdateRelease offeredUpdate,readyUpdate;string updateFolder="",updateError="";bool updateBusy;TextBox log;System.Windows.Forms.Timer ticker;
 volatile bool stopping=true;volatile Mux mux;IDisposable transport;int generation,attemptEpoch,kind;string chosenInput="",chosenOutput="";volatile string relayDiagnostics="";
 readonly TransferRateSampler transferRates=new TransferRateSampler(Stopwatch.Frequency);
 readonly object connectionGate=new object();
 readonly HelperRequestGate helperRequests=new HelperRequestGate();
 TcpAdbCableTarget tcpAdbTarget;Mux tcpAdbSession;bool preparingPhone;string connectionSnapshotPath,accessSnapshotPath;

 public MainForm(bool autoStart){
  // Resizing must repaint the full rounded surface, including its old pixels.
  SetStyle(ControlStyles.AllPaintingInWmPaint|ControlStyles.OptimizedDoubleBuffer|ControlStyles.ResizeRedraw,true);
  Text="USB Bridge";ClientSize=new Size(820,Math.Min(758,Math.Max(620,Screen.PrimaryScreen.WorkingArea.Height-70)));MinimumSize=new Size(740,Math.Min(710,Screen.PrimaryScreen.WorkingArea.Height-12));AutoScroll=true;AutoScrollMinSize=new Size(0,758);AutoScaleDimensions=new SizeF(96,96);AutoScaleMode=AutoScaleMode.Dpi;Font=new Font("Segoe UI",10);BackColor=page;ForeColor=ink;StartPosition=FormStartPosition.CenterScreen;
  Controls.Add(Label("USB Bridge",30,30,24,FontStyle.Bold,750,48));Controls.Add(Label("Интернет компьютера  →  телефон  →  Wi-Fi",12,32,74,FontStyle.Regular,750,30));

  CardPanel sourceCard=new CardPanel();sourceCard.SetBounds(28,116,764,205);sourceCard.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;Controls.Add(sourceCard);
  sourceCard.Controls.Add(Label("Интернет на компьютере",11,20,17,FontStyle.Bold,720,26));
  adapters=new ComboBox();adapters.SetBounds(20,49,724,36);adapters.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;adapters.DropDownStyle=ComboBoxStyle.DropDownList;adapters.DrawMode=DrawMode.OwnerDrawFixed;adapters.ItemHeight=25;adapters.DrawItem+=delegate(object sender,DrawItemEventArgs e){if(e.Index<0)return;using(SolidBrush brush=new SolidBrush((e.State&DrawItemState.Selected)!=0&&e.State!=DrawItemState.ComboBoxEdit?Color.FromArgb(229,243,236):Color.White))e.Graphics.FillRectangle(brush,e.Bounds);string value=adapters.Items[e.Index].ToString();TextRenderer.DrawText(e.Graphics,value,Font,new Rectangle(e.Bounds.X+9,e.Bounds.Y,e.Bounds.Width-16,e.Bounds.Height),ink,TextFormatFlags.VerticalCenter|TextFormatFlags.EndEllipsis);};sourceCard.Controls.Add(adapters);
  sourceCard.Controls.Add(Label("Обычно ничего выбирать не нужно. Подключите телефон кабелем\nи откройте USB Bridge на нём.",10,20,94,FontStyle.Regular,720,43));
  connect=Button("Подключить",20,148,724,true);connect.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;connect.Click+=delegate{if(stopping)Start();else Stop();};sourceCard.Controls.Add(connect);

  CardPanel proofCard=new CardPanel();proofCard.SetBounds(28,337,764,244);proofCard.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;Controls.Add(proofCard);
  status=Label("Готов к подключению",17,20,16,FontStyle.Bold,720,52);proofCard.Controls.Add(status);
  detail=Label("Телефон сам проверит, что интернет идёт через этот компьютер.",10,20,70,FontStyle.Regular,720,54);detail.ForeColor=muted;proofCard.Controls.Add(detail);
  source=Label("Источник Windows будет показан после подключения.",10,20,129,FontStyle.Bold,720,48);proofCard.Controls.Add(source);
  wifiState=Label("Точка Wi-Fi настраивается в приложении телефона.",10,20,181,FontStyle.Regular,720,30);wifiState.ForeColor=muted;proofCard.Controls.Add(wifiState);
  stats=new TransferLabel();stats.Font=new Font("Segoe UI",9.5f);stats.SetBounds(20,215,720,24);stats.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;stats.ForeColor=muted;proofCard.Controls.Add(stats);

  ModernButton settings=Button("Подключение",28,600,250,false);settings.Click+=delegate{Settings();};Controls.Add(settings);
  ModernButton help=Button("Как запустить",290,600,230,false);help.Click+=delegate{MessageBox.Show("1. Соедините компьютер и телефон кабелем.\n2. На телефоне включите «USB-модем». Если Windows не видит его или теряет интернет, выберите «MIDI».\n3. Нажмите «Подключить» в обоих приложениях. Режим кабеля определится сам.\n4. Дождитесь зелёной отметки проверки.\n5. На телефоне нажмите «Включить Wi-Fi».\n\nЕсли раздача потребует системный доступ, приложение на телефоне покажет нужный шаг.","Как запустить");};Controls.Add(help);
  ModernButton report=Button("Диагностика",532,600,260,false);report.Anchor=AnchorStyles.Top|AnchorStyles.Right;report.Click+=delegate{SaveReport();};Controls.Add(report);
  // Equal responsive columns prevent the right button covering the middle one
  // when the window is reduced to its supported minimum width.
  TableLayoutPanel actions=new TableLayoutPanel();actions.SetBounds(28,600,764,44);actions.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;actions.ColumnCount=3;actions.RowCount=1;actions.Margin=Padding.Empty;actions.Padding=Padding.Empty;actions.RowStyles.Add(new RowStyle(SizeType.Percent,100));
  for(int column=0;column<3;column++)actions.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100f/3));
  Control[] actionButtons={settings,help,report};for(int column=0;column<3;column++){Control button=actionButtons[column];button.Dock=DockStyle.Fill;button.Margin=new Padding(column==0?0:6,0,column==2?0:6,0);actions.Controls.Add(button,column,0);}Controls.Add(actions);
  log=new TextBox();log.SetBounds(28,644,764,30);log.Multiline=true;log.ReadOnly=true;log.ScrollBars=ScrollBars.Vertical;log.Visible=false;Controls.Add(log);
  CardPanel updateCard=new CardPanel();updateCard.SetBounds(28,656,764,66);updateCard.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;Controls.Add(updateCard);updateText=Label("Обновления проверяются автоматически",10,20,13,565,42);updateText.ForeColor=muted;updateCard.Controls.Add(updateText);updateButton=Button("Проверить",598,11,146,false);updateButton.Anchor=AnchorStyles.Top|AnchorStyles.Right;updateButton.Click+=delegate{DownloadUpdate();};updateCard.Controls.Add(updateButton);Controls.Add(Label("Версия 0.15.7",9,30,733,750,22));

   FormClosing+=delegate{if(updates!=null)updates.Dispose();ticker.Stop();Stop();};ticker=new System.Windows.Forms.Timer();ticker.Interval=200;ticker.Tick+=delegate{UpdateTransferRates();UpdateUpdateControls();};ticker.Start();RefreshDevices();if(autoStart)Shown+=delegate{BeginInvoke((MethodInvoker)Start);};
 }

 internal void EnableUpdates(){if(updates!=null)return;updates=new UpdateClient(delegate(UpdateRelease release,string error){Ui(delegate{if(offeredUpdate==null||release==null||release.Code>=offeredUpdate.Code)offeredUpdate=release;updateError=error;UpdateUpdateControls();});});}
 void UpdateUpdateControls(){if(updateButton==null)return;string text="Обновления проверяются автоматически",button="Проверить";bool enabled=!updateBusy;if(updateBusy){text="Загружаем обновление… Интернет по USB продолжает работать.";button="Загрузка…";}else if(readyUpdate!=null){text="Версия "+readyUpdate.Name+" готова. "+(stopping?"Можно перезапустить приложение.":"Сначала остановите подключение.");button="Установить";enabled=stopping;}else if(offeredUpdate!=null){text="Доступна версия "+offeredUpdate.Name+". "+(stopping?"Нажмите «Обновить».":"Обновление после остановки подключения.");button="Обновить";enabled=stopping;if(updateError.Length!=0)text=updateError;}else if(updateError.Length!=0)text=updateError;if(updateText.Text!=text)updateText.Text=text;if(updateButton.Text!=button)updateButton.Text=button;updateButton.Enabled=enabled&&!preparingPhone;}
 void DownloadUpdate(){if(updates==null||preparingPhone)return;if(readyUpdate!=null){InstallUpdate();return;}if(offeredUpdate==null){updateError="Проверяем обновления…";UpdateUpdateControls();updates.CheckNow();return;}if(!stopping||updateBusy)return;UpdateRelease selected=offeredUpdate;updateBusy=true;UpdateUpdateControls();if(!updates.Download(selected,delegate(string folder,Exception error){Ui(delegate{updateBusy=false;if(error!=null){updateError="Обновление не загрузилось. Повторите позже.";DiagnosticLog.Write("Update download failed: "+error.Message);UpdateUpdateControls();return;}readyUpdate=selected;updateFolder=folder;UpdateUpdateControls();if(stopping)InstallUpdate();});})){updateBusy=false;updateError="Проверка уже идёт. Повторите через несколько секунд.";UpdateUpdateControls();}}
 void InstallUpdate(){if(preparingPhone||!stopping||readyUpdate==null||updateFolder.Length==0)return;try{UpdateInstaller.ValidateInstalled(updateFolder,readyUpdate);UpdateInstaller.Launch(updateFolder);Close();}catch(Exception error){DiagnosticLog.Write("Update apply failed: "+error.Message);MessageBox.Show("Не удалось запустить обновление. Текущая версия продолжит работать.","USB Bridge");}}
 Label Label(string text,float size,int x,int y,int width,int height){return Label(text,size,x,y,size>=17?FontStyle.Bold:FontStyle.Regular,width,height);}
  Label Label(string text,float size,int x,int y,FontStyle style,int width,int height){Label label=new Label();label.Text=text;label.Font=new Font("Segoe UI",size,style);label.SetBounds(x,y,width,height);label.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;label.BackColor=Color.Transparent;label.AutoEllipsis=false;return label;}
 ModernButton Button(string text,int x,int y,int width,bool primary){ModernButton b=new ModernButton(primary);b.Text=text;b.SetBounds(x,y,width,44);b.Font=new Font("Segoe UI",10.5f,primary?FontStyle.Bold:FontStyle.Regular);return b;}
 static string Format(long value){return value>=1048576?(value/1048576.0).ToString("0.0")+" МБ":(value/1024.0).ToString("0.0")+" КБ";}
 void UpdateTransferRates(){Mux m=mux;string next="";if(!stopping&&m!=null&&!m.Closed){TransferRates rates=transferRates.Sample(m,Interlocked.Read(ref m.Sent),Interlocked.Read(ref m.Received),Stopwatch.GetTimestamp());next="На телефон: "+Format((long)rates.DisplayToPhone)+"/с  ·  С телефона: "+Format((long)rates.DisplayFromPhone)+"/с  ·  Всего: "+Format(rates.Total);}else transferRates.Reset();if(stats.Text!=next)stats.Text=next;}
 void Ui(MethodInvoker action){if(IsDisposed||!IsHandleCreated)return;try{BeginInvoke(action);}catch(InvalidOperationException){}}
 void Report(string message){DiagnosticLog.Write(message);Ui(delegate{if(log.TextLength>18000)log.Clear();log.AppendText(DateTime.Now.ToString("HH:mm:ss")+"  "+message+Environment.NewLine);});}
 protected override void WndProc(ref Message message){if(message.Msg==SingleInstance.ActivateMessage){if(WindowState==FormWindowState.Minimized)WindowState=FormWindowState.Normal;Show();Activate();BringToFront();return;}base.WndProc(ref message);}
 bool RunAlive(int gen){return !stopping&&Thread.VolatileRead(ref generation)==gen;}
 bool AttemptAlive(int gen,int attempt){return RunAlive(gen)&&Thread.VolatileRead(ref attemptEpoch)==attempt;}
 void AttemptUi(int gen,int attempt,MethodInvoker action){Ui(delegate{if(AttemptAlive(gen,attempt))action();});}
 void OwnTransport(int gen,int attempt,IDisposable value){lock(connectionGate){if(!AttemptAlive(gen,attempt))throw new IOException("Подключение отменено");transport=value;}}
 void SessionStatus(int gen,int attempt,Mux session,string link,string message){
  if(!AttemptAlive(gen,attempt)||mux!=session)return;
  if(message=="HELPER|START"){StartHelper(session,gen);return;}
  string[] fields=message.Split('|');
  if(fields.Length>=4&&fields[0]=="STATE")Report("Телефон: "+message);
  Ui(delegate{
   // The UI queue may outlive both a cable and an automatic retry. Never turn
   // green from a frame belonging to a closed or replaced authenticated Mux.
   if(!AttemptAlive(gen,attempt)||mux!=session)return;
   if(session.Closed){transferRates.Reset();status.Text="Связь с телефоном потеряна · подключаем заново";status.ForeColor=ink;detail.Text="Передача остановлена. Повторное подключение выполняется автоматически.";wifiState.Text="Раздача остановлена.";stats.Text="";return;}
   if(fields.Length<4||fields[0]!="STATE")return;
   bool ok=fields[1]=="1";status.Text=ok?"✓ "+fields[2]:fields[2];status.ForeColor=ok?green:ink;
   string proof=fields.Length>4&&!String.IsNullOrEmpty(fields[4])?fields[4]:(ok?"Интернет проверен через этот компьютер.":"Проверяем интернет компьютера.");
   if(proof.IndexOf("HTTPS",StringComparison.OrdinalIgnoreCase)>=0||proof.IndexOf("Тест №",StringComparison.OrdinalIgnoreCase)>=0)proof=ok?"Интернет проверен через этот компьютер.":"Проверяем интернет компьютера.";
   string channel=fields.Length>5&&!String.IsNullOrEmpty(fields[5])?fields[5]:link;
   if(channel=="USB-сеть")channel="USB-модем";
   detail.Text=proof+"\nПодключение: "+channel;wifiState.Text=fields[3];
  });
 }
 void RefreshDevices(){if(!stopping)return;object selected=adapters.SelectedItem;adapters.Items.Clear();adapters.Items.Add("Автоматически — рекомендуется");foreach(Adapter a in NetworkCatalog.All())if(!a.UsbPhone)adapters.Items.Add(a);adapters.SelectedIndex=0;if(selected is Adapter){foreach(object item in adapters.Items)if(item is Adapter&&((Adapter)item).Address.Equals(((Adapter)selected).Address))adapters.SelectedItem=item;if(adapters.SelectedIndex==0){adapters.Items.Add(selected);adapters.SelectedItem=selected;}}}
 static string PortName(string value){int p=value.IndexOf('·');return (p<0?value:value.Substring(p+1)).Trim();}
 static bool MatchMidi(string selectedIn,string selectedOut,out uint input,out uint output){input=output=0;string[] inputs=MidiDevice.Inputs(),outputs=MidiDevice.Outputs();foreach(string i in inputs)foreach(string o in outputs){bool manual=selectedIn!=""&&selectedOut!="";if(manual?(i==selectedIn&&o==selectedOut):String.Equals(PortName(i),PortName(o),StringComparison.OrdinalIgnoreCase)){input=uint.Parse(i.Split(' ')[0]);output=uint.Parse(o.Split(' ')[0]);return true;}}return false;}

 void Start(){
  if(!stopping||preparingPhone)return;
  RefreshDevices();Adapter selected=adapters.SelectedItem as Adapter;IPAddress requestedAddress=selected==null?null:selected.Address;if(selected==null)selected=NetworkCatalog.Choose(NetworkCatalog.All());
  string sourceIp=Environment.GetEnvironmentVariable("USBBRIDGE_SOURCE_IP");
  if(!String.IsNullOrEmpty(sourceIp))foreach(Adapter candidate in NetworkCatalog.All())if(candidate.Address.ToString()==sourceIp){selected=candidate;requestedAddress=candidate.Address;break;}
  if(selected==null){status.Text="Не найден интернет компьютера";status.ForeColor=Color.FromArgb(171,86,19);detail.Text="Подключите компьютер к своей сети и повторите попытку.";return;}
  int gen;lock(connectionGate){stopping=false;gen=Interlocked.Increment(ref generation);}int wanted=kind;string inName=chosenInput,outName=chosenOutput;connect.Text="Остановить";adapters.Enabled=false;status.Text="Ищем телефон по кабелю…";status.ForeColor=ink;detail.Text="На телефоне включите USB-модем или выберите MIDI и нажмите «Подключить».";source.Text="Источник Windows: "+selected.Name;wifiState.Text="Ждём подключения телефона.";
  relayDiagnostics="";Report("Начало подключения "+gen+"; режим "+wanted+"; источник "+(requestedAddress==null?"автоматически":requestedAddress.ToString())+"; CLR "+Environment.Version+"; processBits="+(IntPtr.Size*8));
  Wire.Run(delegate{
   while(RunAlive(gen)){int attempt;lock(connectionGate){if(!RunAlive(gen))break;attempt=Interlocked.Increment(ref attemptEpoch);}IDisposable local=null;Mux current=null;System.Threading.Timer deadline=null;CablePresence cable=null;Relay relay=null;
    try{
     List<Adapter> choices=NetworkCatalog.All();Adapter initialPreferred=NetworkCatalog.Choose(choices);IPAddress initialPreferredAddress=initialPreferred==null?null:initialPreferred.Address;
     CableAlive alive=delegate{return AttemptAlive(gen,attempt);};string uplinkReason;
     if(NetworkCatalog.PhoneOwnsDefault(choices))AttemptUi(gen,attempt,delegate{status.Text="Проверяем интернет компьютера";status.ForeColor=ink;detail.Text="Выбираем подключение ПК отдельно от USB-кабеля телефона.";wifiState.Text="";});
     Adapter adapter=NetworkCatalog.SelectVerifiedUplink(choices,requestedAddress,null,8000,alive,UsbNetwork.VerifyUplink,out uplinkReason);
     if(adapter==null){Report("Источник пока не готов: "+uplinkReason);AttemptUi(gen,attempt,delegate{status.Text="Ждём интернет компьютера";status.ForeColor=ink;detail.Text=uplinkReason;wifiState.Text="USB-модем можно оставить включённым. Поиск продолжится автоматически.";});Thread.Sleep(2000);continue;}
     if(adapter.UplinkCheckDeferred)Report(uplinkReason);
     uint input=0,output=0;Adapter usb=null;SecureWire wire=null;string link="";
     UsbNetworkLink usbLink=NetworkCatalog.CanUseUsbNetwork(wanted,choices)?UsbNetwork.TryOpen(choices,alive,delegate(IDisposable opening){OwnTransport(gen,attempt,opening);},Report):null;
     if(usbLink!=null){local=usbLink;OwnTransport(gen,attempt,usbLink);usb=usbLink.Adapter;NetworkCatalog.RememberPhone(usb);choices=NetworkCatalog.All();
      if(!NetworkCatalog.StillAvailable(adapter,choices,usb)||(!adapter.UplinkVerified&&!adapter.UplinkCheckDeferred&&NetworkCatalog.RequiresUplinkVerification(adapter,choices)))adapter=NetworkCatalog.SelectVerifiedUplink(choices,requestedAddress,usb,8000,alive,UsbNetwork.VerifyUplink,out uplinkReason);
      if(adapter==null){Report("Телефон найден; источник ПК недоступен: "+uplinkReason);AttemptUi(gen,attempt,delegate{status.Text="Телефон найден · ждём интернет ПК";status.ForeColor=ink;detail.Text=uplinkReason;wifiState.Text="Телефон не используется как источник интернета.";});continue;}
      wire=usbLink.Wire;link="USB-модем";
     }else if(wanted!=2&&MatchMidi(inName,outName,out input,out output)){
      MidiDevice device=new MidiDevice(input,output);local=device;OwnTransport(gen,attempt,device);IDisposable opening=local;deadline=new System.Threading.Timer(delegate(object unused){try{opening.Dispose();}catch{}},null,15000,Timeout.Infinite);device.Pipe.Connect();
      wire=new SecureWire(device.Pipe,UsbPair.Connect(device.Pipe),true);deadline.Dispose();deadline=null;link="MIDI";
     }else{AttemptUi(gen,attempt,delegate{status.Text="Ждём телефон";status.ForeColor=ink;detail.Text=wanted==2?"USB-модем телефона пока не найден. Включите его и откройте USB Bridge на телефоне.":"Включите USB-модем или MIDI на телефоне и нажмите «Подключить». Режим определится сам.";wifiState.Text="";});Thread.Sleep(2000);continue;}
     if(!alive()){wire.Dispose();break;}
     relay=new Relay(adapter.Address,adapter.Index,adapter.Dns);relay.Log=Report;
     if(Environment.GetEnvironmentVariable("USBBRIDGE_TRACE_UDP")=="1"){
      relay.Trace=true;
      relay.Log=delegate(string line){Report(line);try{File.AppendAllText(Path.Combine(Path.GetTempPath(),"usbbridge-udp-trace.txt"),DateTime.Now.ToString("HH:mm:ss")+" "+line+Environment.NewLine);}catch{}};
     }
     current=new Mux(wire);Mux captured=current;current.Accepted=relay.Serve;
     current.Status=delegate(string message){SessionStatus(gen,attempt,captured,link,message);};
      lock(connectionGate){if(!alive())throw new IOException("Подключение отменено");mux=current;tcpAdbSession=current;tcpAdbTarget=usbLink==null?null:TcpAdbCableTarget.FromAuthenticatedLink(usbLink.Adapter.Address,usbLink.Peer,usbLink.Adapter.Mask,usbLink.Adapter.Index,usbLink.Adapter.Id,usbLink.Adapter.UsbPhone);}
      string activeMidiInput=link=="MIDI"?MidiPort(input,MidiDevice.Inputs()):"",activeMidiOutput=link=="MIDI"?MidiPort(output,MidiDevice.Outputs()):"";cable=new CablePresence(usb,activeMidiInput,activeMidiOutput);
      // Queue the initial state BEFORE starting the reader; otherwise a valid
      // STATE reply can be overwritten by the older "checking" UI update.
      AttemptUi(gen,attempt,delegate{if(mux!=captured||captured.Closed)return;transferRates.Reset();transferRates.Sample(captured,0,0,Stopwatch.GetTimestamp());source.Text="Интернет компьютера: "+adapter.Name;status.Text="Кабель подключён · проверяем интернет";status.ForeColor=ink;detail.Text="Подключение: "+link;wifiState.Text="Результат появится автоматически.";stats.Text="";});
      current.Start();current.Send(8,0,Encoding.UTF8.GetBytes("LIMIT|"+Mux.MaximumChannels));current.Send(8,0,Encoding.UTF8.GetBytes("SOURCE|"+adapter.Name));Report("Канал "+link+"; сеанс "+gen+"/"+attempt+"; источник "+adapter.Name+"; адрес "+adapter.Address+"; интерфейс "+adapter.Index+"; DNS "+adapter.Dns);
      int monitoringTicks=0;while(!current.Closed&&alive()){
       Thread.Sleep(200);if(++monitoringTicks%3!=0)continue;
       relayDiagnostics=relay.Diagnostics+(local is MidiDevice?"; MIDI retries="+((MidiDevice)local).Pipe.RetryCount:"");
       List<Adapter> now=NetworkCatalog.All();
       if(cable.Removed(cable.Present(now,MidiDevice.Inputs(),MidiDevice.Outputs()),Environment.TickCount)){StopForCableLoss(gen,attempt);break;}
       if(monitoringTicks<12)continue;monitoringTicks=0;
       if(!NetworkCatalog.StillAvailable(adapter,now,usb)){Report("Выбранное подключение ПК или его DNS изменилось; повторяем поиск");break;}
       // A USB default route alone cannot invalidate our explicit source+ifindex.
       // A genuinely new preferred NON-phone network (for example a PC VPN)
       // still causes an intentional reconnect in automatic mode.
       Adapter preferred=NetworkCatalog.Choose(now);
       if(requestedAddress==null&&preferred!=null&&preferred.Preferred&&NetworkCatalog.IsUsableUplink(preferred,now,usb)&&!preferred.Address.Equals(adapter.Address)&&!preferred.Address.Equals(initialPreferredAddress)){Report("Изменился выбранный Windows источник; переподключаем");break;}
      }
      AttemptUi(gen,attempt,delegate{status.Text="Связь с телефоном потеряна · подключаем заново";status.ForeColor=ink;detail.Text="Передача остановлена. Повторное подключение выполняется автоматически.";wifiState.Text="Раздача остановлена.";stats.Text="";});
    }catch(Exception e){Report("Сеанс "+gen+"/"+attempt+": "+e.Message);AttemptUi(gen,attempt,delegate{status.Text="Соединение пока не установлено";status.ForeColor=ink;detail.Text="Проверяем USB-модем и MIDI. Оставьте USB Bridge открытым на телефоне; поиск повторится автоматически.";wifiState.Text="";stats.Text="";});}
    finally{if(deadline!=null)deadline.Dispose();if(current!=null)current.Dispose();if(local!=null)try{local.Dispose();}catch{}if(relay!=null)Report("Сеанс "+gen+"/"+attempt+" завершён; "+relay.Diagnostics);lock(connectionGate){if(AttemptAlive(gen,attempt)){transport=null;if(mux==current){mux=null;tcpAdbTarget=null;tcpAdbSession=null;}}}}
    // EOF usually arrives before the next device monitor tick. Check presence
    // here too, otherwise an unplugged phone remains in an endless search loop.
    while(cable!=null&&AttemptAlive(gen,attempt)){bool present=cable.Present(NetworkCatalog.All(),MidiDevice.Inputs(),MidiDevice.Outputs());if(present){cable.Removed(true,Environment.TickCount);break;}if(cable.Removed(false,Environment.TickCount)){StopForCableLoss(gen,attempt);break;}Thread.Sleep(200);}
    if(RunAlive(gen))Thread.Sleep(2000);
   }
  });
 }

 void StopForCableLoss(int gen,int attempt){int ended;lock(connectionGate){if(!AttemptAlive(gen,attempt))return;stopping=true;ended=Interlocked.Increment(ref generation);mux=null;tcpAdbTarget=null;tcpAdbSession=null;transport=null;}Report("USB-устройство отключено; поиск остановлен");Ui(delegate{if(!stopping||generation!=ended)return;transferRates.Reset();connect.Text="Подключить";adapters.Enabled=true;status.Text="Телефон отключён";status.ForeColor=ink;detail.Text="Подключите кабель и нажмите «Подключить».";wifiState.Text="Раздача остановлена.";source.Text="Источник Windows будет показан после подключения.";stats.Text="";});}

 void StartHelper(Mux session,int gen){
  TcpAdbCableTarget capturedTarget;lock(connectionGate)capturedTarget=tcpAdbSession==session?tcpAdbTarget:null;
  HelperSessionAlive alive=delegate{return !stopping&&gen==generation&&mux==session&&!session.Closed;};
  if(!alive())return;
  // Status arrives only after the cable's authenticated handshake. Keep ADB and
  // result sending off the Mux reader and UI threads; failures never stop relay.
  helperRequests.Enqueue(alive,delegate{try{string result="";for(int attempt=0;attempt<3&&alive();attempt++){result=AdbHelper.TryStartCable(alive,capturedTarget,delegate(string progress){if(alive())session.Send(8,0,Encoding.UTF8.GetBytes("HELPER|RESULT|"+progress));});if(!result.StartsWith("На телефоне разрешите USB-отладку",StringComparison.Ordinal)||attempt==2)break;session.Send(8,0,Encoding.UTF8.GetBytes("HELPER|RESULT|Разрешите USB-отладку на телефоне. После подтверждения продолжим автоматически."));for(int tick=0;tick<20&&alive();tick++)Thread.Sleep(500);}if(alive()){Report("Настройка раздачи: "+result);session.Send(8,0,Encoding.UTF8.GetBytes("HELPER|RESULT|"+result));}}catch(Exception){Report("Настройка раздачи завершилась после отключения кабеля.");}});
 }
  static string MidiPort(uint id,string[] ports){string prefix=id+" · ";foreach(string port in ports)if(port.StartsWith(prefix,StringComparison.Ordinal))return port;return "";}
  void Stop(){transferRates.Reset();Mux old;IDisposable device;lock(connectionGate){stopping=true;Interlocked.Increment(ref generation);old=mux;mux=null;tcpAdbTarget=null;tcpAdbSession=null;device=transport;transport=null;}Wire.Run(delegate{if(old!=null)try{old.Dispose();}catch{}if(device!=null)try{device.Dispose();}catch{}});if(connect!=null)connect.Text="Подключить";if(adapters!=null)adapters.Enabled=true;if(status!=null){status.Text="Отключено";status.ForeColor=ink;}if(detail!=null)detail.Text="Для подключения нажмите кнопку на телефоне и компьютере.";if(source!=null)source.Text="Источник Windows будет показан после подключения.";if(wifiState!=null)wifiState.Text="Точка Wi-Fi настраивается в приложении телефона.";if(stats!=null)stats.Text="";}
 void Settings(){
  if(!stopping||preparingPhone){MessageBox.Show("Сначала остановите подключение и дождитесь проверки служебного доступа.","Подключение");return;}
  using(Form f=new Form()){f.Text="Подключение";f.ClientSize=new Size(600,Math.Min(360,Math.Max(300,Screen.PrimaryScreen.WorkingArea.Height-100)));f.AutoScroll=true;f.AutoScrollMinSize=new Size(0,360);f.Font=Font;f.BackColor=page;f.ForeColor=ink;f.StartPosition=FormStartPosition.CenterParent;f.FormBorderStyle=FormBorderStyle.FixedDialog;f.MaximizeBox=f.MinimizeBox=false;
   f.Controls.Add(Label("Режим кабеля",12,22,20,550,28));ComboBox mode=new ComboBox();mode.SetBounds(22,53,556,32);mode.DropDownStyle=ComboBoxStyle.DropDownList;mode.Items.AddRange(new object[]{"Автоматически — USB-модем, затем MIDI","Только MIDI","Только USB-модем"});mode.SelectedIndex=kind;f.Controls.Add(mode);
   f.Controls.Add(Label("MIDI-порты телефона (обычно определяются сами)",10,22,105,550,28));ComboBox mi=new ComboBox(),mo=new ComboBox();mi.DropDownStyle=mo.DropDownStyle=ComboBoxStyle.DropDownList;mi.SetBounds(22,139,556,31);mo.SetBounds(22,183,556,31);mi.Items.Add("Автоматически");mo.Items.Add("Автоматически");mi.Items.AddRange(MidiDevice.Inputs());mo.Items.AddRange(MidiDevice.Outputs());mi.SelectedIndex=mo.SelectedIndex=0;if(mi.Items.Contains(chosenInput))mi.SelectedItem=chosenInput;if(mo.Items.Contains(chosenOutput))mo.SelectedItem=chosenOutput;f.Controls.Add(mi);f.Controls.Add(mo);
   f.Controls.Add(Label("USB-модем быстрее. MIDI подходит, если компьютер не видит USB-модем.",10,22,229,550,35));
   ModernButton access=Button("Служебный доступ",22,279,270,false);access.Click+=delegate{PhoneAccess(f);};f.Controls.Add(access);
   ModernButton ok=Button("Сохранить",396,279,182,true);ok.Click+=delegate{if(preparingPhone)return;kind=mode.SelectedIndex;chosenInput=mi.SelectedIndex>0?mi.Text:"";chosenOutput=mo.SelectedIndex>0?mo.Text:"";f.DialogResult=DialogResult.OK;};f.Controls.Add(ok);
   if(connectionSnapshotPath==null)f.ShowDialog(this);else{f.Show();Application.DoEvents();using(Bitmap bitmap=new Bitmap(f.Width,f.Height)){f.DrawToBitmap(bitmap,new Rectangle(Point.Empty,bitmap.Size));bitmap.Save(connectionSnapshotPath,System.Drawing.Imaging.ImageFormat.Png);}f.Close();}
  }RefreshDevices();
 }
 public void SnapshotConnection(string path){connectionSnapshotPath=path;try{Settings();}finally{connectionSnapshotPath=null;}}
 public void SnapshotAccess(string path){accessSnapshotPath=path;try{PhoneAccess(this);}finally{accessSnapshotPath=null;}}
 void PhoneAccess(Form owner){
  if(preparingPhone||!stopping)return;
  using(Form f=new Form()){f.Text="Служебный доступ";f.ClientSize=new Size(600,Math.Min(440,Math.Max(320,Screen.PrimaryScreen.WorkingArea.Height-100)));f.AutoScroll=true;f.AutoScrollMinSize=new Size(0,440);f.Font=Font;f.BackColor=page;f.ForeColor=ink;f.StartPosition=FormStartPosition.CenterParent;f.FormBorderStyle=FormBorderStyle.FixedDialog;f.MaximizeBox=f.MinimizeBox=false;
   f.Controls.Add(Label("Для VPN и раздачи Wi-Fi",15,22,22,550,34));
   f.Controls.Add(Label("Обычно здесь ничего нажимать не нужно: кнопка «Подключить» сама выбирает доступный способ связи с телефоном.",11,22,70,550,65));
   f.Controls.Add(Label("Если на ПК нет USB ADB-драйвера, один раз разрешите доступ на компьютере, где USB-отладка работает. После этого на рабочем ПК достаточно «Подключить» через USB-модем.",10,22,145,550,90));
   ModernButton enable=Button("Разрешить доступ",22,253,270,true),disable=Button("Отключить доступ",308,253,270,false),close=Button("Закрыть",396,376,182,false);f.Controls.Add(enable);f.Controls.Add(disable);f.Controls.Add(close);
   f.Controls.Add(Label("Android может отключить этот доступ после перезагрузки телефона. Через MIDI доступ без драйвера не работает.",10,22,312,550,55));
   enable.Click+=delegate{ChangePhoneAccess(f,true,enable,disable,close);};disable.Click+=delegate{ChangePhoneAccess(f,false,enable,disable,close);};close.Click+=delegate{f.Close();};if(accessSnapshotPath==null)f.ShowDialog(owner);else{f.Show();Application.DoEvents();using(Bitmap bitmap=new Bitmap(f.Width,f.Height)){f.DrawToBitmap(bitmap,new Rectangle(Point.Empty,bitmap.Size));bitmap.Save(accessSnapshotPath,System.Drawing.Imaging.ImageFormat.Png);}f.Close();}
  }
 }
 void ChangePhoneAccess(Form owner,bool enable,Control first,Control second,Control save){
  if(preparingPhone||!stopping||updateBusy||readyUpdate!=null)return;
  string explanation=enable?"Подключите только один телефон к этому ПК и разрешите USB-отладку.\n\nВключится сетевая отладка Android на порту 5555. Команды защищены разрешением ключа компьютера, но сам TCP-канал не шифруется и может быть доступен через другие сети телефона. Используйте только доверенные сети.\n\nUSB Bridge сможет запускать служебный доступ через USB-модем, если на другом ПК нет USB ADB-драйвера. Никаких флажков на рабочем ПК не потребуется. Android может сбросить разрешение после перезагрузки телефона.\n\nРазрешить этот доступ?":"Отключить служебный доступ без USB-драйвера на порту 5555? Нужен ПК с работающей USB-отладкой.\n\nНа ПК без ADB-драйвера помощник через USB-модем больше не запустится. Другие способы сетевой отладки эта кнопка не меняет.";
  if(MessageBox.Show(owner,explanation,"Служебный доступ",MessageBoxButtons.YesNo,MessageBoxIcon.Question,MessageBoxDefaultButton.Button2)!=DialogResult.Yes)return;
  preparingPhone=true;first.Enabled=second.Enabled=save.Enabled=false;first.Text="Подождите…";
  Wire.Run(delegate{string result=TcpAdbHelper.SetPreparedMode(enable,delegate{return !IsDisposed&&!owner.IsDisposed;});Report("Служебный доступ: "+result);Ui(delegate{preparingPhone=false;if(owner.IsDisposed)return;first.Text="Разрешить доступ";first.Enabled=second.Enabled=save.Enabled=true;MessageBox.Show(owner,result,"Служебный доступ");});});
 }
  void SaveReport(){using(SaveFileDialog d=new SaveFileDialog()){d.FileName="USBBridge-report.txt";d.Filter="Текст|*.txt";if(d.ShowDialog()==DialogResult.OK)try{File.WriteAllText(d.FileName,"USB Bridge 0.15.7\r\n"+Environment.OSVersion+"\r\nCLR "+Environment.Version+"; processBits="+(IntPtr.Size*8)+"\r\n"+stats.Text+"\r\n"+relayDiagnostics+"\r\n"+source.Text+"\r\n"+status.Text+"\r\n"+detail.Text+"\r\n"+wifiState.Text+"\r\n"+NetworkCatalog.Describe(NetworkCatalog.All())+"\r\n"+log.Text,Encoding.UTF8);}catch(Exception e){MessageBox.Show("Не удалось сохранить: "+e.Message);}}}
 public void SnapshotUpdate(string path,bool active,bool ready){offeredUpdate=new UpdateRelease{Code=158,Name="0.15.8"};if(ready)readyUpdate=offeredUpdate;stopping=!active;UpdateUpdateControls();Snapshot(path);}
 public void Snapshot(string path){Show();Application.DoEvents();using(Bitmap b=new Bitmap(Width,Height)){DrawToBitmap(b,new Rectangle(0,0,Width,Height));b.Save(path);}Close();}
}

static class Program {
 [STAThread]static int Main(string[] args){try{
  if(args.Length==1&&args[0]=="--check-updates"){UpdateHttpResult result=UpdateHttp.GetManifest(UpdateManifest.ManifestUrl,"");UpdateRelease release=UpdateManifest.Read(result.Bytes);Console.WriteLine("Update manifest HTTP "+result.Status+"; current="+UpdateManifest.CurrentName+"; latest="+(release==null?"none":release.Name)+"; offer="+UpdateClient.ShouldOffer(release));return 0;}
  if(args.Length>0&&args[0]=="--networks"){foreach(Adapter a in NetworkCatalog.All())Console.WriteLine(a.Name+" | "+a.Address+" | usb="+a.UsbPhone+" | preferred="+a.Preferred);return 0;}
  if(args.Length>0&&args[0]=="--test-relay"){string host=args[1],code=args[2];using(TcpClient c=new TcpClient()){c.Connect(host,int.Parse(args[3]));c.NoDelay=true;c.ReceiveTimeout=25000;Relay relay=new Relay(args.Length>4?IPAddress.Parse(args[4]):null,args.Length>6?int.Parse(args[6]):0,IPAddress.Parse(args.Length>5?args[5]:"1.1.1.1"));relay.Log=Console.WriteLine;relay.Trace=true;string secret=code=="auto"?UsbPair.Connect(c.GetStream()):code;Mux m=new Mux(new SecureWire(c.GetStream(),secret,true));m.Accepted=relay.Serve;m.Status=Console.WriteLine;m.Start();m.Send(8,0,Encoding.UTF8.GetBytes("LIMIT|"+Mux.MaximumChannels));m.Send(8,0,Encoding.UTF8.GetBytes("SOURCE|Тестовое подключение ПК"));while(!m.Closed)Thread.Sleep(500);return 0;}}
  Application.EnableVisualStyles();Application.SetCompatibleTextRenderingDefault(false);
  if(args.Length==2&&(args[0]=="--snapshot"||args[0]=="--snapshot-narrow")){using(MainForm preview=new MainForm(false)){if(args[0]=="--snapshot-narrow")preview.Width=preview.MinimumSize.Width;preview.Snapshot(args[1]);}return 0;}
  if(args.Length==2&&args[0]=="--snapshot-connection"){using(MainForm preview=new MainForm(false))preview.SnapshotConnection(args[1]);return 0;}
  if(args.Length==2&&args[0]=="--snapshot-access"){using(MainForm preview=new MainForm(false))preview.SnapshotAccess(args[1]);return 0;}
  if(!UpdateInstaller.WaitForPrevious(args))return 1;if(UpdateInstaller.TryForward(args))return 0;
  if(args.Length==2&&args[0].StartsWith("--snapshot-update-",StringComparison.Ordinal)){using(MainForm preview=new MainForm(false)){if(args[0].EndsWith("narrow",StringComparison.Ordinal))preview.Width=preview.MinimumSize.Width;preview.SnapshotUpdate(args[1],args[0].IndexOf("active",StringComparison.Ordinal)>=0,args[0].IndexOf("ready",StringComparison.Ordinal)>=0);}return 0;}
  using(SingleInstance instance=new SingleInstance("Local\\USBBridge.Desktop.1")){
   if(!instance.IsOwner){SingleInstance.ActivateExisting();return 0;}
   DiagnosticLog.Write("USB Bridge 0.15.7 started; PID "+System.Diagnostics.Process.GetCurrentProcess().Id);
   using(MainForm form=new MainForm(args.Length==0)){form.Shown+=delegate{UpdateInstaller.ConfirmCurrentInstallation();};form.EnableUpdates();Application.Run(form);}return 0;
  }
 }catch(Exception e){if(args.Length>0){try{Console.Error.WriteLine(e.ToString());}catch{}return 1;}MessageBox.Show("USB Bridge не удалось запустить.\r\n"+e.Message,"USB Bridge");return 1;}}
}
}
