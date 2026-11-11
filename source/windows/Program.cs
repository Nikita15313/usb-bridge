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
 public static GraphicsPath Rounded(Rectangle bounds,int radius){GraphicsPath path=new GraphicsPath();int size=Math.Max(1,Math.Min(Math.Min(bounds.Width,bounds.Height),radius*2));Rectangle arc=new Rectangle(bounds.X,bounds.Y,size,size);path.AddArc(arc,180,90);arc.X=bounds.Right-size;path.AddArc(arc,270,90);arc.Y=bounds.Bottom-size;path.AddArc(arc,0,90);arc.X=bounds.X;path.AddArc(arc,90,90);path.CloseFigure();return path;}
}
public sealed class CardPanel:UserControl {
 public Color Fill=Color.White,Border=BluePalette.Line;public bool Gradient;public int CornerRadius=20;
 public CardPanel(){SetStyle(ControlStyles.AllPaintingInWmPaint|ControlStyles.OptimizedDoubleBuffer|ControlStyles.UserPaint|ControlStyles.ResizeRedraw,true);BackColor=BluePalette.Page;}
 internal void PaintSurface(Graphics g){g.SmoothingMode=SmoothingMode.AntiAlias;using(SolidBrush b=new SolidBrush(Parent==null?BluePalette.Page:Parent.BackColor))g.FillRectangle(b,ClientRectangle);Rectangle r=new Rectangle(0,0,Math.Max(1,Width-1),Math.Max(1,Height-1));using(GraphicsPath p=UiShape.Rounded(r,(int)Math.Round(CornerRadius*g.DpiX/96f))){if(Gradient){using(LinearGradientBrush b=new LinearGradientBrush(r,Fill,Color.FromArgb(250,252,255),90))g.FillPath(b,p);}else using(SolidBrush b=new SolidBrush(Fill))g.FillPath(b,p);using(Pen line=new Pen(Border))g.DrawPath(line,p);}}
 protected override void OnPaintBackground(PaintEventArgs e){using(SolidBrush behind=new SolidBrush(Parent==null?BluePalette.Page:Parent.BackColor))e.Graphics.FillRectangle(behind,ClientRectangle);}
 // UserControl may omit its background pass during a live buffered repaint.
 // Paint the complete opaque surface here too, so rounded outside corners
 // never expose pixels retained from the native window backing store.
 protected override void OnPaint(PaintEventArgs e){PaintSurface(e.Graphics);}
}
public sealed class ModernButton:Control {
 public bool Primary,Navigation,Selected,White,ArrowEnd,Choice;public string GlyphName="",SubText="";bool hover;
 public ModernButton(bool primary){Primary=primary;Cursor=Cursors.Hand;TabStop=true;AccessibleRole=AccessibleRole.PushButton;SetStyle(ControlStyles.AllPaintingInWmPaint|ControlStyles.OptimizedDoubleBuffer|ControlStyles.UserPaint|ControlStyles.Selectable|ControlStyles.ResizeRedraw,true);}
 protected override void OnTextChanged(EventArgs e){base.OnTextChanged(e);Invalidate();}
 protected override void OnEnabledChanged(EventArgs e){base.OnEnabledChanged(e);Invalidate();}
 protected override void OnGotFocus(EventArgs e){base.OnGotFocus(e);Invalidate();}
 protected override void OnLostFocus(EventArgs e){base.OnLostFocus(e);Invalidate();}
 protected override void OnMouseDown(MouseEventArgs e){Focus();base.OnMouseDown(e);}
 protected override void OnKeyDown(KeyEventArgs e){if(e.KeyCode==Keys.Space||e.KeyCode==Keys.Enter){OnClick(EventArgs.Empty);e.Handled=true;}base.OnKeyDown(e);}
 protected override void OnMouseEnter(EventArgs e){hover=true;Invalidate();base.OnMouseEnter(e);}protected override void OnMouseLeave(EventArgs e){hover=false;Invalidate();base.OnMouseLeave(e);}

 float paintScale=1;int P(float value){return (int)Math.Round(value*paintScale);}
 protected override void OnPaint(PaintEventArgs e){
  Graphics g=e.Graphics;paintScale=g.DpiX/96f;BluePalette.Backdrop(g,this);g.SmoothingMode=SmoothingMode.AntiAlias;int pad=P(18);
  Color fill=Primary?(hover?Color.FromArgb(33,86,234):BluePalette.Accent):(Selected?BluePalette.Pale:hover?Color.FromArgb(246,249,255):Color.White);if(!Enabled)fill=Color.FromArgb(241,244,250);
  Rectangle r=new Rectangle(0,0,Math.Max(1,Width-1),Math.Max(1,Height-1));using(GraphicsPath path=UiShape.Rounded(r,P(Navigation?13:Choice?15:12))){using(SolidBrush brush=new SolidBrush(fill))g.FillPath(brush,path);if(!Primary&&(!Navigation||Selected||hover))using(Pen line=new Pen(Selected?Color.FromArgb(158,185,255):BluePalette.Line,Selected?1.5f*paintScale:paintScale))g.DrawPath(line,path);if(Focused)using(Pen line=new Pen(Primary?Color.FromArgb(200,218,255):Color.FromArgb(147,175,255),paintScale))using(GraphicsPath focus=UiShape.Rounded(new Rectangle(P(3),P(3),Math.Max(1,Width-P(7)),Math.Max(1,Height-P(7))),P(9)))g.DrawPath(line,focus);}
  Color text=Enabled?(Primary?Color.White:Selected||Navigation&&hover?BluePalette.Accent:BluePalette.Ink):BluePalette.Muted;
  if(Choice){BlueGlyph.Draw(g,GlyphName,new RectangleF(pad,pad,P(25),P(25)),Selected?BluePalette.Accent:BluePalette.Muted);using(Font title=new Font(Font.FontFamily,Font.Size,FontStyle.Bold))TextRenderer.DrawText(g,Text,title,new Rectangle(pad,Height-P(57),Width-pad*2,P(25)),text,TextFormatFlags.EndEllipsis|TextFormatFlags.VerticalCenter);using(Font sub=new Font(Font.FontFamily,Math.Max(8,Font.Size-1)))TextRenderer.DrawText(g,SubText,sub,new Rectangle(pad,Height-P(31),Width-pad*2,P(24)),BluePalette.Muted,TextFormatFlags.EndEllipsis|TextFormatFlags.VerticalCenter);if(Selected){using(SolidBrush brush=new SolidBrush(BluePalette.Accent))g.FillEllipse(brush,Width-P(32),P(15),P(17),P(17));using(Pen check=new Pen(Color.White,1.5f*paintScale))g.DrawLines(check,new Point[]{new Point(Width-P(28),P(23)),new Point(Width-P(24),P(27)),new Point(Width-P(19),P(20))});}return;}
  if(Navigation){int icon=Math.Min(P(24),Height-P(20));BlueGlyph.Draw(g,GlyphName,new RectangleF(pad,(Height-icon)/2,icon,icon),Selected?BluePalette.Accent:BluePalette.Muted);if(Text.Length!=0){TextRenderer.DrawText(g,Text,Font,new Rectangle(pad+icon+P(13),0,Math.Max(1,Width-pad-icon-P(22)),Height),Selected?BluePalette.Accent:Color.FromArgb(112,128,155),TextFormatFlags.VerticalCenter|TextFormatFlags.EndEllipsis);if(Selected)using(SolidBrush brush=new SolidBrush(BluePalette.Accent))g.FillEllipse(brush,Width-P(19),Height/2-P(3),P(6),P(6));}return;}
  if(SubText.Length!=0){int icon=P(36);using(SolidBrush brush=new SolidBrush(BluePalette.Pale))using(GraphicsPath path=UiShape.Rounded(new Rectangle(pad,(Height-icon)/2,icon,icon),P(12)))g.FillPath(brush,path);BlueGlyph.Draw(g,GlyphName,new RectangleF(pad+P(8),(Height-icon)/2+P(8),P(20),P(20)),BluePalette.Accent);int left=pad+icon+P(16);using(Font title=new Font(Font.FontFamily,Font.Size,FontStyle.Bold))TextRenderer.DrawText(g,Text,title,new Rectangle(left,Height/2-P(23),Math.Max(1,Width-left-P(38)),P(25)),text,TextFormatFlags.EndEllipsis|TextFormatFlags.VerticalCenter);using(Font sub=new Font(Font.FontFamily,Math.Max(8,Font.Size-1)))TextRenderer.DrawText(g,SubText,sub,new Rectangle(left,Height/2+P(2),Math.Max(1,Width-left-P(38)),P(24)),BluePalette.Muted,TextFormatFlags.EndEllipsis|TextFormatFlags.VerticalCenter);BlueGlyph.Draw(g,"chevron",new RectangleF(Width-P(28),Height/2-P(8),P(16),P(16)),Color.FromArgb(159,176,203));return;}
  Rectangle textBounds=ClientRectangle;if(ArrowEnd)textBounds.Width=Math.Max(1,Width-P(20));TextRenderer.DrawText(g,Text,Font,textBounds,text,TextFormatFlags.HorizontalCenter|TextFormatFlags.VerticalCenter|TextFormatFlags.EndEllipsis);if(ArrowEnd)BlueGlyph.Draw(g,"arrow",new RectangleF(Width-P(29),Height/2-P(8),P(16),P(16)),text);
 }

}

// Frequent counter updates repaint only their own opaque, buffered rectangle.
// Transparent labels can ask the rounded parent to repaint on every update.
internal sealed class TransferLabel:Label {
 public string EmptyText="Скорость появится после подключения";
 public TransferLabel(){SetStyle(ControlStyles.AllPaintingInWmPaint|ControlStyles.OptimizedDoubleBuffer|ControlStyles.UserPaint|ControlStyles.ResizeRedraw,true);BackColor=Color.White;}
 protected override void OnPaint(PaintEventArgs e){e.Graphics.Clear(BackColor);TextRenderer.DrawText(e.Graphics,String.IsNullOrEmpty(Text)?EmptyText:Text,Font,ClientRectangle,String.IsNullOrEmpty(Text)?BluePalette.Muted:ForeColor,TextFormatFlags.VerticalCenter|TextFormatFlags.EndEllipsis);}
}

public sealed class MainForm:Form {
 readonly Color green=BluePalette.Success,ink=BluePalette.Ink,muted=BluePalette.Muted,page=BluePalette.Page;
 ComboBox adapters;Label status,detail,stats,source,wifiState,updateText;ModernButton connect,updateButton;UpdateClient updates;UpdateRelease offeredUpdate,readyUpdate;string updateFolder="",updateError="";bool updateBusy;TextBox log;System.Windows.Forms.Timer ticker;
 volatile bool stopping=true;volatile Mux mux;IDisposable transport;int generation,attemptEpoch,kind;string chosenInput="",chosenOutput="";volatile string relayDiagnostics="";
 readonly TransferRateSampler transferRates=new TransferRateSampler(Stopwatch.Frequency);
 readonly object connectionGate=new object();
 readonly HelperRequestGate helperRequests=new HelperRequestGate();
 TcpAdbCableTarget tcpAdbTarget;Mux tcpAdbSession;bool preparingPhone;string connectionSnapshotPath,accessSnapshotPath;


 SmoothPanel sidebar,canvas;CardPanel heroCard,sourceCard,trafficCard,updatesCard,helpCard;BrandMark brand;
 HeroIllustration illustration;StatePill statePill;Label brandTitle,brandCaption,navCaption,sideVersion,breadcrumb,eyebrow,heading,subtitle,topState,sourceHeading,sourceCaption,sourceField,trafficHeading,helpTitle,helpText;
 ModernButton homeNav,connectionNav,diagnosticsNav,preferencesNav,sideHelp,heroHelp,connectionTile,diagnosticsTile;
 float uiScale=1;bool arranging,previewOnly;Icon brandIcon;
 int D(float value){return Math.Max(0,(int)Math.Round(value*uiScale));}
 Label PutLabel(Control parent,string text,float size,FontStyle style){Label result=Label(text,size,0,0,style,10,10);result.Anchor=AnchorStyles.Top|AnchorStyles.Left;result.ForeColor=ink;parent.Controls.Add(result);return result;}
 void Place(Control control,int x,int y,int width,int height){control.SetBounds(D(x),D(y),D(width),D(height));}
 ModernButton Navigation(string caption,string glyph,EventHandler action){ModernButton button=Button(caption,0,0,1,false);button.Navigation=true;button.GlyphName=glyph;button.Anchor=AnchorStyles.Top|AnchorStyles.Left;button.Click+=action;sidebar.Controls.Add(button);return button;}
 public MainForm(bool autoStart){
  SetStyle(ControlStyles.AllPaintingInWmPaint|ControlStyles.OptimizedDoubleBuffer|ControlStyles.ResizeRedraw,true);
  using(Graphics desktop=Graphics.FromHwnd(IntPtr.Zero))uiScale=desktop.DpiX/96f;
  AutoScaleMode=AutoScaleMode.None;Text="USB Bridge";Rectangle screen=Screen.PrimaryScreen.WorkingArea;
  ClientSize=new Size(Math.Min(D(1200),screen.Width-D(40)),Math.Min(D(720),screen.Height-D(52)));
  MinimumSize=new Size(Math.Min(D(740),screen.Width-12),Math.Min(D(570),screen.Height-12));
  Font=new Font("Segoe UI",10);BackColor=page;ForeColor=ink;StartPosition=FormStartPosition.CenterScreen;
  try{using(Stream iconStream=typeof(MainForm).Assembly.GetManifestResourceStream("UsbBridge.BrandIcon")){if(iconStream!=null){using(Icon loaded=new Icon(iconStream))brandIcon=(Icon)loaded.Clone();Icon=brandIcon;}}}catch{}
  canvas=new SmoothPanel();canvas.Dock=DockStyle.Fill;canvas.AutoScroll=true;canvas.BackColor=page;Controls.Add(canvas);
  sidebar=new SmoothPanel();sidebar.Dock=DockStyle.Left;sidebar.BackColor=Color.White;Controls.Add(sidebar);
  sidebar.Paint+=delegate(object sender,PaintEventArgs e){using(Pen line=new Pen(BluePalette.Line))e.Graphics.DrawLine(line,sidebar.Width-1,0,sidebar.Width-1,sidebar.Height);};
  brand=new BrandMark();sidebar.Controls.Add(brand);brandTitle=PutLabel(sidebar,"USB Bridge",14.5f,FontStyle.Bold);brandCaption=PutLabel(sidebar,"Просто. По кабелю.",9,FontStyle.Regular);brandCaption.ForeColor=muted;
  navCaption=PutLabel(sidebar,"ПРИЛОЖЕНИЕ",8.5f,FontStyle.Bold);navCaption.ForeColor=Color.FromArgb(167,180,202);
  homeNav=Navigation("Главная","home",delegate{canvas.AutoScrollPosition=Point.Empty;});homeNav.Selected=true;
  connectionNav=Navigation("Подключение","phone",delegate{Settings();});
  diagnosticsNav=Navigation("Диагностика","pulse",delegate{ShowDiagnostics();});
  preferencesNav=Navigation("Настройки","settings",delegate{ShowPreferences();});
  helpCard=new CardPanel();helpCard.Fill=Color.FromArgb(251,252,255);sidebar.Controls.Add(helpCard);
  helpTitle=PutLabel(helpCard,"Нужна помощь?",10.5f,FontStyle.Bold);helpText=PutLabel(helpCard,"Короткая инструкция\nпо первому подключению.",9,FontStyle.Regular);helpText.ForeColor=muted;
  sideHelp=Button("Открыть инструкцию",0,0,1,false);sideHelp.Font=new Font("Segoe UI",9,FontStyle.Bold);sideHelp.ArrowEnd=true;sideHelp.Click+=delegate{ShowHelp();};helpCard.Controls.Add(sideHelp);
  sideVersion=PutLabel(sidebar,"Версия 0.15.8",8.5f,FontStyle.Regular);sideVersion.ForeColor=Color.FromArgb(157,172,197);
  breadcrumb=PutLabel(canvas,"Приложение   ›   Главная",9,FontStyle.Bold);breadcrumb.ForeColor=Color.FromArgb(137,153,179);
  eyebrow=PutLabel(canvas,"ВСЁ ПОД КОНТРОЛЕМ",8.5f,FontStyle.Bold);eyebrow.ForeColor=Color.FromArgb(94,130,228);
  heading=PutLabel(canvas,"Подключение",28,FontStyle.Bold);
  subtitle=PutLabel(canvas,"Интернет на телефоне через USB. Просто и без лишних настроек.",10.5f,FontStyle.Regular);subtitle.ForeColor=muted;
  topState=PutLabel(canvas,"Ожидаем телефон",9,FontStyle.Bold);topState.ForeColor=muted;topState.TextAlign=ContentAlignment.MiddleRight;
  heroCard=new CardPanel();heroCard.Fill=Color.FromArgb(235,241,255);heroCard.Border=Color.FromArgb(215,227,255);heroCard.Gradient=true;canvas.Controls.Add(heroCard);
  statePill=new StatePill();statePill.Text="Ожидание подключения";heroCard.Controls.Add(statePill);
  status=PutLabel(heroCard,"Телефон пока\nне подключён",23,FontStyle.Bold);
  detail=PutLabel(heroCard,"Подключите телефон кабелем USB\nи откройте на нём USB Bridge.",10.5f,FontStyle.Regular);detail.ForeColor=Color.FromArgb(116,138,172);
  illustration=new HeroIllustration();heroCard.Controls.Add(illustration);
  connect=Button("Подключить",0,0,1,true);connect.ArrowEnd=true;connect.Anchor=AnchorStyles.Top|AnchorStyles.Left;connect.Click+=delegate{if(stopping)Start();else Stop();};heroCard.Controls.Add(connect);
  heroHelp=Button("Как подключить",0,0,1,false);heroHelp.Anchor=AnchorStyles.Top|AnchorStyles.Left;heroHelp.Click+=delegate{ShowHelp();};heroCard.Controls.Add(heroHelp);
  sourceCard=new CardPanel();canvas.Controls.Add(sourceCard);sourceHeading=PutLabel(sourceCard,"Источник интернета",12,FontStyle.Bold);
  sourceCaption=PutLabel(sourceCard,"Выберите подключение на компьютере",9.5f,FontStyle.Regular);sourceCaption.ForeColor=muted;
  sourceField=PutLabel(sourceCard,"Подключение",9.5f,FontStyle.Bold);sourceField.ForeColor=Color.FromArgb(77,98,134);
  ModernComboBox adapterPicker=new ModernComboBox();adapterPicker.AdapterChoices=true;adapterPicker.ItemHeight=D(42);adapterPicker.DropDownHeight=D(250);adapters=adapterPicker;adapters.Anchor=AnchorStyles.Top|AnchorStyles.Left;sourceCard.Controls.Add(adapters);
  source=PutLabel(sourceCard,"Программа сама выберет подходящий источник. Обычно менять его не нужно.",9.5f,FontStyle.Regular);source.ForeColor=Color.FromArgb(116,138,172);
  connectionTile=Button("Параметры подключения",0,0,1,false);connectionTile.SubText="Способ соединения и MIDI";connectionTile.GlyphName="settings";connectionTile.Anchor=AnchorStyles.Top|AnchorStyles.Left;connectionTile.Click+=delegate{Settings();};canvas.Controls.Add(connectionTile);
  diagnosticsTile=Button("Проверка соединения",0,0,1,false);diagnosticsTile.SubText="Диагностика и отчёт";diagnosticsTile.GlyphName="pulse";diagnosticsTile.Anchor=AnchorStyles.Top|AnchorStyles.Left;diagnosticsTile.Click+=delegate{ShowDiagnostics();};canvas.Controls.Add(diagnosticsTile);
  trafficCard=new CardPanel();canvas.Controls.Add(trafficCard);trafficHeading=PutLabel(trafficCard,"Передача по кабелю",9,FontStyle.Bold);trafficHeading.ForeColor=Color.FromArgb(77,98,134);
  stats=new TransferLabel();stats.Font=new Font("Segoe UI",10,FontStyle.Bold);stats.ForeColor=Color.FromArgb(68,100,155);stats.Anchor=AnchorStyles.Top|AnchorStyles.Left;trafficCard.Controls.Add(stats);
  wifiState=PutLabel(trafficCard,"Точка Wi-Fi настраивается в приложении телефона.",9,FontStyle.Regular);wifiState.ForeColor=muted;
  updatesCard=new CardPanel();updatesCard.Fill=Color.FromArgb(250,252,255);canvas.Controls.Add(updatesCard);
  updateText=PutLabel(updatesCard,"Обновления проверяются автоматически",9,FontStyle.Regular);updateText.ForeColor=muted;
  updateButton=Button("Проверить",0,0,1,false);updateButton.Font=new Font("Segoe UI",9.5f,FontStyle.Bold);updateButton.Anchor=AnchorStyles.Top|AnchorStyles.Left;updateButton.Click+=delegate{DownloadUpdate();};updatesCard.Controls.Add(updateButton);
  log=new TextBox();log.Multiline=true;log.ReadOnly=true;log.Visible=false;canvas.Controls.Add(log);
  status.TextChanged+=delegate{UpdateVisualState();};status.ForeColorChanged+=delegate{UpdateVisualState();};detail.TextChanged+=delegate{FitHeroLabels();};
  Resize+=delegate{ArrangeBlueInterface();};canvas.Resize+=delegate{ArrangeBlueInterface();};
  FormClosing+=delegate{if(updates!=null)updates.Dispose();ticker.Stop();Stop();};FormClosed+=delegate{if(brandIcon!=null)brandIcon.Dispose();};
  ticker=new System.Windows.Forms.Timer();ticker.Interval=200;ticker.Tick+=delegate{UpdateTransferRates();UpdateUpdateControls();};ticker.Start();RefreshDevices();ArrangeBlueInterface();
  if(autoStart)Shown+=delegate{BeginInvoke((MethodInvoker)Start);};
 }
 static void FitLabel(Label label,float preferred,float minimum,FontStyle style){if(label==null||label.Width<30||label.Height<20)return;float size=preferred;Font candidate=null;for(;;){candidate=new Font("Segoe UI",size,style);Size required=TextRenderer.MeasureText(label.Text,candidate,new Size(Math.Max(1,label.Width-6),int.MaxValue),TextFormatFlags.WordBreak|TextFormatFlags.NoPrefix|TextFormatFlags.TextBoxControl);if(required.Height<=label.Height-3||size<=minimum)break;candidate.Dispose();size=Math.Max(minimum,size-.5f);}if(Math.Abs(label.Font.Size-size)<.01f){candidate.Dispose();return;}Font previous=label.Font;label.Font=candidate;previous.Dispose();}
 void FitHeroLabels(){FitLabel(status,23,12.5f,FontStyle.Bold);FitLabel(detail,10.5f,9,FontStyle.Regular);}
 void UpdateVisualState(){if(statePill==null||topState==null)return;FitHeroLabels();bool verified=status.ForeColor.ToArgb()==green.ToArgb();statePill.Text=verified?"Компьютер подключён":stopping?"Ожидание подключения":"Подключаем телефон";statePill.Dot=verified?green:stopping?Color.FromArgb(250,165,83):BluePalette.Accent;statePill.Invalidate();topState.Text=verified?"●  Интернет подтверждён":stopping?"●  Ожидаем телефон":"●  Подключаем телефон";topState.ForeColor=verified?green:muted;}
 void ArrangeBlueInterface(){
  if(arranging||canvas==null||sidebar==null||heroCard==null)return;arranging=true;
  int originalCanvasWidth=canvas.ClientSize.Width;
  try{bool compact=ClientSize.Width<D(1080);bool shortHeader=canvas.ClientSize.Height/uiScale<520;sidebar.Width=D(compact?78:224);SuspendLayout();canvas.SuspendLayout();
   Place(brand,compact?16:22,30,compact?46:48,compact?46:48);brandTitle.Visible=brandCaption.Visible=navCaption.Visible=!compact;
   Place(brandTitle,84,34,136,32);Place(brandCaption,84,66,134,22);Place(navCaption,25,shortHeader?103:143,170,20);
   ModernButton[] navs={homeNav,connectionNav,diagnosticsNav,preferencesNav};string[] captions={"Главная","Подключение","Диагностика","Настройки"};for(int i=0;i<navs.Length;i++){navs[i].Text=compact?"":captions[i];int navY=compact?124+i*58:shortHeader?136+i*54:184+i*58;Place(navs[i],compact?9:18,navY,compact?60:188,48);navs[i].AccessibleName=captions[i];}
   int sidebarHeight=(int)(sidebar.ClientSize.Height/uiScale);helpCard.Visible=!compact&&sidebarHeight>=620;if(helpCard.Visible){Place(helpCard,18,sidebarHeight-183,188,135);Place(helpTitle,15,15,160,25);Place(helpText,15,42,160,42);Place(sideHelp,10,91,168,32);}sideVersion.Text=compact?"0.15.8":"Версия 0.15.8";Place(sideVersion,compact?12:25,sidebarHeight-31,compact?59:175,22);
   int width=(int)(canvas.ClientSize.Width/uiScale),pad=28,scroll=(int)(canvas.AutoScrollPosition.Y/uiScale);
   // Reserve the vertical bar before creating it; otherwise its 17 pixels can
   // temporarily turn the correctly sized page into a horizontal scroll area.
   int expectedHeight=(width-pad*2>=840?763:1007)-(shortHeader?46:0);if(!canvas.VerticalScroll.Visible&&canvas.ClientSize.Height<D(expectedHeight))width-=(int)Math.Ceiling(SystemInformation.VerticalScrollBarWidth/uiScale);
   int body=Math.Max(300,width-pad*2);bool columns=body>=840;
   Place(breadcrumb,pad,(shortHeader?16:23)+scroll,Math.Max(150,body-240),22);Place(topState,pad+body-234,(shortHeader?16:23)+scroll,234,22);eyebrow.Visible=!shortHeader;Place(eyebrow,pad,66+scroll,body,20);Place(heading,pad-1,(shortHeader?40:88)+scroll,body,49);Place(subtitle,pad,(shortHeader?92:140)+scroll,body,29);
   int heroTop=shortHeader?132:178,heroWidth=columns?body-300:body,heroHeight=284,sourceY=heroTop,sourceWidth=280;
   Place(heroCard,pad,heroTop+scroll,heroWidth,heroHeight);bool art=heroWidth>=500;illustration.Visible=art;int words=art?heroWidth-248:heroWidth-48;
   Place(statePill,24,22,Math.Min(233,heroWidth-48),31);Place(status,25,70,words,91);Place(detail,26,165,words,50);Place(illustration,heroWidth-236,20,232,254);FitHeroLabels();
   int first=Math.Min(166,(heroWidth-62)/2);Place(connect,26,226,first,42);Place(heroHelp,38+first,226,Math.Min(158,heroWidth-first-64),42);
   if(columns)Place(sourceCard,pad+heroWidth+20,heroTop+scroll,sourceWidth,heroHeight);else{sourceY=heroTop+heroHeight+18;sourceWidth=body;Place(sourceCard,pad,sourceY+scroll,sourceWidth,226);}
   Place(sourceHeading,22,23,sourceWidth-44,27);Place(sourceCaption,22,59,sourceWidth-44,42);Place(sourceField,22,columns?114:102,sourceWidth-44,22);Place(adapters,20,columns?143:128,sourceWidth-40,54);Place(source,22,columns?216:187,sourceWidth-44,columns?54:31);
   int next=columns?heroTop+heroHeight+18:sourceY+244,gap=16,half=(body-gap)/2;Place(connectionTile,pad,next+scroll,half,70);Place(diagnosticsTile,pad+half+gap,next+scroll,body-half-gap,70);
   next+=86;Place(trafficCard,pad,next+scroll,body,100);Place(trafficHeading,20,14,body-40,18);Place(stats,20,36,body-40,24);Place(wifiState,20,67,body-40,18);
   next+=116;Place(updatesCard,pad,next+scroll,body,59);Place(updateText,19,10,Math.Max(80,body-178),40);Place(updateButton,body-141,11,122,37);
   canvas.AutoScrollMinSize=new Size(0,D(next+81));
  }finally{canvas.ResumeLayout();ResumeLayout();arranging=false;if(IsHandleCreated&&!IsDisposed&&canvas.ClientSize.Width!=originalCanvasWidth)BeginInvoke((MethodInvoker)ArrangeBlueInterface);}
 }
 void ShowHelp(){using(Form f=BlueDialog("Как подключить",590,455)){f.Controls.Add(DialogLabel("Просто. По кабелю.",22,24,24,535,46,FontStyle.Bold));f.Controls.Add(DialogLabel("1. Подключите телефон USB-кабелем.\n\n2. На телефоне включите «USB-модем».\n    Если компьютер его не видит — выберите MIDI.\n\n3. Нажмите «Подключить» в USB Bridge на обоих устройствах.\n\n4. Дождитесь подтверждения интернета.\n\n5. Для других устройств нажмите «Включить Wi-Fi» на телефоне.",10.5f,24,87,535,285,FontStyle.Regular));ModernButton close=DialogButton("Понятно",404,390,150,true);close.Click+=delegate{f.Close();};f.Controls.Add(close);f.ShowDialog(this);}}
 void ShowDiagnostics(){using(Form f=BlueDialog("Диагностика",760,540)){f.Controls.Add(DialogLabel("Проверка соединения",21,25,22,690,44,FontStyle.Bold));f.Controls.Add(DialogLabel("Сохраните отчёт, чтобы разобраться с подключением удалённо.",10,26,72,690,35,FontStyle.Regular));TextBox text=new TextBox();text.ReadOnly=true;text.Multiline=true;text.ScrollBars=ScrollBars.Both;text.WordWrap=false;text.BackColor=Color.FromArgb(249,251,255);text.ForeColor=ink;text.BorderStyle=BorderStyle.FixedSingle;text.Font=new Font("Consolas",9);text.SetBounds(D(26),D(121),D(704),D(324));text.Text=DiagnosticReport();f.Controls.Add(text);ModernButton save=DialogButton("Сохранить отчёт",26,469,190,true);save.Click+=delegate{SaveReport();};f.Controls.Add(save);ModernButton close=DialogButton("Закрыть",581,469,150,false);close.Click+=delegate{f.Close();};f.Controls.Add(close);f.ShowDialog(this);}}
 void ShowPreferences(){using(Form f=BlueDialog("Настройки",590,330)){f.Controls.Add(DialogLabel("Настройки",22,24,24,535,44,FontStyle.Bold));f.Controls.Add(DialogLabel("USB Bridge 0.15.8",11,25,85,535,28,FontStyle.Bold));f.Controls.Add(DialogLabel("Обновления проверяются автоматически, пока программа открыта.\nДоступная версия появится на главной странице.",10,25,126,535,68,FontStyle.Regular));ModernButton check=DialogButton("Проверить обновления",25,236,252,true);check.Enabled=!previewOnly;check.Click+=delegate{f.Close();if(updates!=null){updateError="Проверяем обновления…";UpdateUpdateControls();updates.CheckNow();}};f.Controls.Add(check);ModernButton close=DialogButton("Закрыть",406,236,150,false);close.Click+=delegate{f.Close();};f.Controls.Add(close);f.ShowDialog(this);}}
 Form BlueDialog(string title,int width,int height){Form result=new Form();result.Text=title;Rectangle screen=Screen.FromControl(this).WorkingArea;result.AutoScaleMode=AutoScaleMode.None;result.ClientSize=new Size(Math.Min(D(width),screen.Width-D(48)),Math.Min(D(height),screen.Height-D(88)));result.AutoScroll=true;result.AutoScrollMinSize=new Size(D(width),D(height));result.Font=Font;result.BackColor=Color.White;result.ForeColor=ink;result.StartPosition=FormStartPosition.CenterParent;result.FormBorderStyle=FormBorderStyle.FixedDialog;result.MaximizeBox=result.MinimizeBox=false;if(brandIcon!=null)result.Icon=brandIcon;return result;}
 Label DialogLabel(string text,float size,int x,int y,int width,int height,FontStyle style){Label result=Label(text,size,D(x),D(y),style,D(width),D(height));result.Anchor=AnchorStyles.Top|AnchorStyles.Left;result.ForeColor=size>=11?ink:muted;return result;}
 ModernButton DialogButton(string text,int x,int y,int width,bool primary){ModernButton result=Button(text,D(x),D(y),D(width),primary);result.Height=D(42);result.Anchor=AnchorStyles.Top|AnchorStyles.Left;return result;}

 internal void EnableUpdates(){if(updates!=null)return;updates=new UpdateClient(delegate(UpdateRelease release,string error){Ui(delegate{if(offeredUpdate==null||release==null||release.Code>=offeredUpdate.Code)offeredUpdate=release;updateError=error;UpdateUpdateControls();});});}
 void UpdateUpdateControls(){if(updateButton==null)return;if(previewOnly){updateText.Text="Предпросмотр интерфейса · подключение не запущено";updateButton.Enabled=false;return;}string text="Обновления проверяются автоматически",button="Проверить";bool enabled=!updateBusy;if(updateBusy){text="Загружаем обновление… Интернет по USB продолжает работать.";button="Загрузка…";}else if(readyUpdate!=null){text="Версия "+readyUpdate.Name+" готова. "+(stopping?"Можно перезапустить приложение.":"Сначала остановите подключение.");button="Установить";enabled=stopping;}else if(offeredUpdate!=null){text="Доступна версия "+offeredUpdate.Name+". "+(stopping?"Нажмите «Обновить».":"Обновление после остановки подключения.");button="Обновить";enabled=stopping;if(updateError.Length!=0)text=updateError;}else if(updateError.Length!=0)text=updateError;if(updateText.Text!=text)updateText.Text=text;if(updateButton.Text!=button)updateButton.Text=button;updateButton.Enabled=enabled&&!preparingPhone;}
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
  using(Form f=BlueDialog("Параметры подключения",780,646)){
   f.Controls.Add(DialogLabel("USB BRIDGE",9,27,22,720,23,FontStyle.Bold));f.Controls.Add(DialogLabel("Параметры подключения",22,25,51,724,46,FontStyle.Bold));f.Controls.Add(DialogLabel("Настройки соединения с телефоном",10,27,105,720,29,FontStyle.Regular));
   Panel separator=new Panel();separator.BackColor=BluePalette.Line;separator.SetBounds(0,D(146),D(780),1);f.Controls.Add(separator);
   f.Controls.Add(DialogLabel("Способ подключения",11,27,172,724,27,FontStyle.Bold));
   int selectedMode=kind;ModernButton[] choices=new ModernButton[3];string[] names={"Автоматически","USB-модем","MIDI"},captions={"Лучший вариант","Быстрее","Если USB недоступен"},glyphs={"sparkle","plug","midi"};int[] values={0,2,1};
   for(int i=0;i<3;i++){int choice=i;ModernButton button=DialogButton(names[i],27+i*247,215,232,false);button.Choice=true;button.SubText=captions[i];button.GlyphName=glyphs[i];button.Height=D(130);button.Selected=selectedMode==values[i];button.Click+=delegate{selectedMode=values[choice];for(int n=0;n<choices.Length;n++){choices[n].Selected=selectedMode==values[n];choices[n].Invalidate();}};choices[i]=button;f.Controls.Add(button);}
   f.Controls.Add(DialogLabel("MIDI-порты",11,27,378,720,27,FontStyle.Bold));f.Controls.Add(DialogLabel("Определяются автоматически",9,137,380,590,25,FontStyle.Regular));
   f.Controls.Add(DialogLabel("Входящий порт",9.5f,27,419,347,24,FontStyle.Bold));f.Controls.Add(DialogLabel("Исходящий порт",9.5f,404,419,347,24,FontStyle.Bold));
   ModernComboBox mi=new ModernComboBox(),mo=new ModernComboBox();mi.ItemHeight=mo.ItemHeight=D(30);mi.SetBounds(D(27),D(451),D(347),D(43));mo.SetBounds(D(404),D(451),D(347),D(43));mi.Items.Add("Автоматически");mo.Items.Add("Автоматически");mi.Items.AddRange(MidiDevice.Inputs());mo.Items.AddRange(MidiDevice.Outputs());mi.SelectedIndex=mo.SelectedIndex=0;if(mi.Items.Contains(chosenInput))mi.SelectedItem=chosenInput;if(mo.Items.Contains(chosenOutput))mo.SelectedItem=chosenOutput;f.Controls.Add(mi);f.Controls.Add(mo);
   f.Controls.Add(DialogLabel("MIDI используется, если компьютер не видит телефон как USB-модем.",9,28,513,720,38,FontStyle.Regular));
   Panel footer=new Panel();footer.BackColor=BluePalette.Line;footer.SetBounds(0,D(569),D(780),1);f.Controls.Add(footer);
   ModernButton access=DialogButton("Служебный доступ",27,593,224,false);access.Click+=delegate{PhoneAccess(f);};f.Controls.Add(access);
   ModernButton cancel=DialogButton("Отмена",503,593,114,false);cancel.Click+=delegate{f.Close();};f.Controls.Add(cancel);
   ModernButton save=DialogButton("Сохранить",630,593,121,true);save.Click+=delegate{if(preparingPhone)return;kind=selectedMode;chosenInput=mi.SelectedIndex>0?mi.Text:"";chosenOutput=mo.SelectedIndex>0?mo.Text:"";f.DialogResult=DialogResult.OK;};f.Controls.Add(save);
   if(connectionSnapshotPath==null)f.ShowDialog(this);else{f.Show();Application.DoEvents();using(Bitmap bitmap=new Bitmap(f.Width,f.Height)){f.DrawToBitmap(bitmap,new Rectangle(Point.Empty,bitmap.Size));bitmap.Save(connectionSnapshotPath,System.Drawing.Imaging.ImageFormat.Png);}f.Close();}
  }RefreshDevices();
 }

 public void SnapshotConnection(string path){connectionSnapshotPath=path;try{Settings();}finally{connectionSnapshotPath=null;}}
 public void SnapshotAccess(string path){accessSnapshotPath=path;try{PhoneAccess(this);}finally{accessSnapshotPath=null;}}
 void PhoneAccess(Form owner){
  if(preparingPhone||!stopping)return;
  using(Form f=BlueDialog("Служебный доступ",650,495)){
   f.Controls.Add(DialogLabel("Для VPN и раздачи Wi-Fi",20,26,25,598,43,FontStyle.Bold));
   f.Controls.Add(DialogLabel("Обычно здесь ничего нажимать не нужно: кнопка «Подключить» сама выбирает доступный способ связи с телефоном.",10.5f,27,87,596,67,FontStyle.Regular));
   f.Controls.Add(DialogLabel("Если на ПК нет USB ADB-драйвера, один раз разрешите доступ на компьютере, где USB-отладка работает. После этого на рабочем ПК достаточно «Подключить» через USB-модем.",10,27,164,596,91,FontStyle.Regular));
   ModernButton enable=DialogButton("Разрешить доступ",26,276,290,true),disable=DialogButton("Отключить доступ",333,276,290,false),close=DialogButton("Закрыть",473,435,150,false);f.Controls.Add(enable);f.Controls.Add(disable);f.Controls.Add(close);
   f.Controls.Add(DialogLabel("Android может отключить этот доступ после перезагрузки телефона. Через MIDI доступ без драйвера не работает.",10,27,342,596,67,FontStyle.Regular));
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
 string DiagnosticReport(){return "USB Bridge 0.15.8\r\n"+Environment.OSVersion+"\r\nCLR "+Environment.Version+"; processBits="+(IntPtr.Size*8)+"\r\n"+stats.Text+"\r\n"+relayDiagnostics+"\r\n"+source.Text+"\r\n"+status.Text+"\r\n"+detail.Text+"\r\n"+wifiState.Text+"\r\n"+NetworkCatalog.Describe(NetworkCatalog.All())+"\r\n"+log.Text;}
 void SaveReport(){using(SaveFileDialog d=new SaveFileDialog()){d.FileName="USBBridge-report.txt";d.Filter="Текст|*.txt";if(d.ShowDialog()==DialogResult.OK)try{File.WriteAllText(d.FileName,DiagnosticReport(),Encoding.UTF8);}catch(Exception e){MessageBox.Show("Не удалось сохранить: "+e.Message);}}}
 public void SnapshotUpdate(string path,bool active,bool ready){offeredUpdate=new UpdateRelease{Code=159,Name="0.15.9"};if(ready)readyUpdate=offeredUpdate;stopping=!active;UpdateUpdateControls();Snapshot(path);}
 public void SnapshotVisual(string path,string state){ticker.Stop();if(state.EndsWith("-narrow",StringComparison.Ordinal)){Width=MinimumSize.Width;state=state.Substring(0,state.Length-7);}if(state=="connected"){stopping=false;connect.Text="Остановить";status.Text="Интернет идёт\nчерез компьютер";status.ForeColor=green;detail.Text="Интернет компьютера проверен.\nПодключение: USB-модем";source.Text="Источник компьютера: Ethernet";wifiState.Text="Wi-Fi включён · через VPN телефона";stats.Text="На телефон: 14,8 МБ/с  ·  С телефона: 2,4 МБ/с  ·  Всего: 42,6 МБ";}else if(state=="connecting"){stopping=false;connect.Text="Остановить";status.Text="Ищем телефон\nпо кабелю";status.ForeColor=ink;detail.Text="Проверьте USB-модем на телефоне.\nСпособ соединения выберется сам.";}else if(state=="access"){stopping=false;connect.Text="Остановить";status.Text="Интернет ПК проверен · настройка Android не завершена";status.ForeColor=ink;detail.Text="Интернет компьютера проверен.\nПодключение: USB-модем";}else if(state=="reconnecting"){stopping=false;connect.Text="Остановить";status.Text="Связь с телефоном потеряна · подключаем заново";status.ForeColor=ink;detail.Text="Передача остановлена. Повторное подключение выполняется автоматически.";}else if(state=="error"){status.Text="Телефон\nне отвечает";status.ForeColor=ink;detail.Text="Проверьте кабель и откройте\nUSB Bridge на телефоне.";}UpdateVisualState();Snapshot(path);}
 public void PreviewOnly(){previewOnly=true;Text="USB Bridge — просмотр дизайна";UpdateUpdateControls();}
 public void Snapshot(string path){Show();Application.DoEvents();using(Bitmap b=new Bitmap(Width,Height)){DrawToBitmap(b,new Rectangle(0,0,Width,Height));b.Save(path);}Close();}
}

static class Program {
 [STAThread]static int Main(string[] args){try{
  if(args.Length==1&&args[0]=="--check-updates"){UpdateHttpResult result=UpdateHttp.GetManifest(UpdateManifest.ManifestUrl,"");UpdateRelease release=UpdateManifest.Read(result.Bytes);Console.WriteLine("Update manifest HTTP "+result.Status+"; current="+UpdateManifest.CurrentName+"; latest="+(release==null?"none":release.Name)+"; offer="+UpdateClient.ShouldOffer(release));return 0;}
  if(args.Length>0&&args[0]=="--networks"){foreach(Adapter a in NetworkCatalog.All())Console.WriteLine(a.Name+" | "+a.Address+" | usb="+a.UsbPhone+" | preferred="+a.Preferred);return 0;}
  if(args.Length>0&&args[0]=="--test-relay"){string host=args[1],code=args[2];using(TcpClient c=new TcpClient()){c.Connect(host,int.Parse(args[3]));c.NoDelay=true;c.ReceiveTimeout=25000;Relay relay=new Relay(args.Length>4?IPAddress.Parse(args[4]):null,args.Length>6?int.Parse(args[6]):0,IPAddress.Parse(args.Length>5?args[5]:"1.1.1.1"));relay.Log=Console.WriteLine;relay.Trace=true;string secret=code=="auto"?UsbPair.Connect(c.GetStream()):code;Mux m=new Mux(new SecureWire(c.GetStream(),secret,true));m.Accepted=relay.Serve;m.Status=Console.WriteLine;m.Start();m.Send(8,0,Encoding.UTF8.GetBytes("LIMIT|"+Mux.MaximumChannels));m.Send(8,0,Encoding.UTF8.GetBytes("SOURCE|Тестовое подключение ПК"));while(!m.Closed)Thread.Sleep(500);return 0;}}
  Application.EnableVisualStyles();Application.SetCompatibleTextRenderingDefault(false);
  if(args.Length==1&&args[0]=="--preview"){using(MainForm preview=new MainForm(false)){preview.PreviewOnly();Application.Run(preview);}return 0;}
  if(args.Length==2&&args[0].StartsWith("--snapshot-state-",StringComparison.Ordinal)){using(MainForm preview=new MainForm(false))preview.SnapshotVisual(args[1],args[0].Substring("--snapshot-state-".Length));return 0;}
  if(args.Length==2&&(args[0]=="--snapshot"||args[0]=="--snapshot-narrow")){using(MainForm preview=new MainForm(false)){if(args[0]=="--snapshot-narrow")preview.Width=preview.MinimumSize.Width;preview.Snapshot(args[1]);}return 0;}
  if(args.Length==2&&args[0]=="--snapshot-connection"){using(MainForm preview=new MainForm(false))preview.SnapshotConnection(args[1]);return 0;}
  if(args.Length==2&&args[0]=="--snapshot-access"){using(MainForm preview=new MainForm(false))preview.SnapshotAccess(args[1]);return 0;}
  if(!UpdateInstaller.WaitForPrevious(args))return 1;if(UpdateInstaller.TryForward(args))return 0;
  if(args.Length==2&&args[0].StartsWith("--snapshot-update-",StringComparison.Ordinal)){using(MainForm preview=new MainForm(false)){if(args[0].EndsWith("narrow",StringComparison.Ordinal))preview.Width=preview.MinimumSize.Width;preview.SnapshotUpdate(args[1],args[0].IndexOf("active",StringComparison.Ordinal)>=0,args[0].IndexOf("ready",StringComparison.Ordinal)>=0);}return 0;}
  using(SingleInstance instance=new SingleInstance("Local\\USBBridge.Desktop.1")){
   if(!instance.IsOwner){SingleInstance.ActivateExisting();return 0;}
   DiagnosticLog.Write("USB Bridge 0.15.8 started; PID "+System.Diagnostics.Process.GetCurrentProcess().Id);
   using(MainForm form=new MainForm(args.Length==0)){form.Shown+=delegate{UpdateInstaller.ConfirmCurrentInstallation();};form.EnableUpdates();Application.Run(form);}return 0;
  }
 }catch(Exception e){if(args.Length>0){try{Console.Error.WriteLine(e.ToString());}catch{}return 1;}MessageBox.Show("USB Bridge не удалось запустить.\r\n"+e.Message,"USB Bridge");return 1;}}
}
}
