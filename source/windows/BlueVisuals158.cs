using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace UsbBridge {
internal sealed class SmoothPanel:Panel {
 public SmoothPanel(){SetStyle(ControlStyles.AllPaintingInWmPaint|ControlStyles.OptimizedDoubleBuffer|ControlStyles.ResizeRedraw,true);}
}
internal static class BluePalette {
 internal static readonly Color Accent=Color.FromArgb(47,102,255),Ink=Color.FromArgb(22,37,66),Muted=Color.FromArgb(130,146,173),Page=Color.FromArgb(246,248,253),Line=Color.FromArgb(226,233,246),Pale=Color.FromArgb(238,243,255),Success=Color.FromArgb(34,181,137);
 internal static Color Blend(Color a,Color b,float amount){amount=Math.Max(0,Math.Min(1,amount));return Color.FromArgb((int)(a.R+(b.R-a.R)*amount),(int)(a.G+(b.G-a.G)*amount),(int)(a.B+(b.B-a.B)*amount));}
 internal static void Backdrop(Graphics g,Control child){CardPanel card=child.Parent as CardPanel;if(card!=null){GraphicsState saved=g.Save();g.TranslateTransform(-child.Left,-child.Top);card.PaintSurface(g);g.Restore(saved);}else{using(SolidBrush brush=new SolidBrush(child.Parent==null?Page:child.Parent.BackColor))g.FillRectangle(brush,child.ClientRectangle);}}
}
internal static class BlueGlyph {
 internal static void Draw(Graphics graphics,string name,RectangleF box,Color color){
  GraphicsState saved=graphics.Save();graphics.SmoothingMode=SmoothingMode.AntiAlias;graphics.TranslateTransform(box.X,box.Y);graphics.ScaleTransform(box.Width/24f,box.Height/24f);
  using(Pen pen=new Pen(color,1.8f)){pen.StartCap=pen.EndCap=LineCap.Round;pen.LineJoin=LineJoin.Round;
   if(name=="chain"){using(GraphicsPath left=UiShape.Rounded(new Rectangle(2,7,9,10),3))graphics.DrawPath(pen,left);using(GraphicsPath right=UiShape.Rounded(new Rectangle(13,7,9,10),3))graphics.DrawPath(pen,right);graphics.DrawLine(pen,9,12,15,12);}
   else if(name=="home"){for(int x=3;x<=13;x+=10)for(int y=3;y<=13;y+=10)using(GraphicsPath p=UiShape.Rounded(new Rectangle(x,y,8,8),2))graphics.DrawPath(pen,p);}
   else if(name=="settings"){graphics.DrawEllipse(pen,8,8,8,8);for(int i=0;i<8;i++){double a=i*Math.PI/4;graphics.DrawLine(pen,12+(float)Math.Cos(a)*8,12+(float)Math.Sin(a)*8,12+(float)Math.Cos(a)*10,12+(float)Math.Sin(a)*10);}}
   else if(name=="pulse"){graphics.DrawLines(pen,new PointF[]{new PointF(2,13),new PointF(6,13),new PointF(9,5),new PointF(13,20),new PointF(17,10),new PointF(20,13),new PointF(23,13)});}
   else if(name=="plug"){graphics.DrawLine(pen,8,2,8,8);graphics.DrawLine(pen,16,2,16,8);graphics.DrawLine(pen,5,8,19,8);graphics.DrawLine(pen,6,8,6,12);graphics.DrawArc(pen,6,6,12,12,0,180);graphics.DrawLine(pen,18,8,18,12);graphics.DrawLine(pen,12,18,12,23);}
   else if(name=="phone"){using(GraphicsPath p=UiShape.Rounded(new Rectangle(6,2,12,20),2))graphics.DrawPath(pen,p);graphics.DrawLine(pen,10,5,14,5);graphics.DrawLine(pen,10,19,14,19);}
   else if(name=="midi"){using(GraphicsPath p=UiShape.Rounded(new Rectangle(2,5,20,14),3))graphics.DrawPath(pen,p);for(int x=6;x<20;x+=4)graphics.DrawLine(pen,x,9,x,15);}
   else if(name=="arrow"){graphics.DrawLine(pen,3,12,21,12);graphics.DrawLine(pen,15,6,21,12);graphics.DrawLine(pen,15,18,21,12);}
   else if(name=="chevron"){graphics.DrawLines(pen,new PointF[]{new PointF(9,5),new PointF(16,12),new PointF(9,19)});}
   else if(name=="sparkle"){graphics.DrawPolygon(pen,new PointF[]{new PointF(12,2),new PointF(15,9),new PointF(22,12),new PointF(15,15),new PointF(12,22),new PointF(9,15),new PointF(2,12),new PointF(9,9)});}
   else if(name=="shield"){graphics.DrawPolygon(pen,new PointF[]{new PointF(12,2),new PointF(21,6),new PointF(19,16),new PointF(12,22),new PointF(5,16),new PointF(3,6)});graphics.DrawLines(pen,new PointF[]{new PointF(8,12),new PointF(11,15),new PointF(16,9)});}
   else if(name=="help"){graphics.DrawEllipse(pen,2,2,20,20);graphics.DrawArc(pen,8,6,8,7,180,240);graphics.DrawLine(pen,12,12,12,15);graphics.DrawEllipse(pen,11.5f,18,1,1);}
   else if(name=="wifi"){graphics.DrawArc(pen,1,4,22,22,220,100);graphics.DrawArc(pen,5,8,14,14,220,100);graphics.DrawArc(pen,9,12,6,6,220,100);graphics.DrawEllipse(pen,11.3f,19,1.4f,1.4f);}
   else if(name=="globe"){graphics.DrawEllipse(pen,2,2,20,20);graphics.DrawEllipse(pen,7,2,10,20);graphics.DrawLine(pen,2,12,22,12);}
  }graphics.Restore(saved);
 }
 internal static void Brand(Graphics graphics,Rectangle box){graphics.SmoothingMode=SmoothingMode.AntiAlias;using(GraphicsPath p=UiShape.Rounded(box,Math.Max(4,(int)(box.Width*.28f))))using(SolidBrush brush=new SolidBrush(BluePalette.Accent))graphics.FillPath(brush,p);GraphicsState saved=graphics.Save();graphics.TranslateTransform(box.X,box.Y);graphics.ScaleTransform(box.Width/64f,box.Height/64f);using(Pen pen=new Pen(Color.White,3)){pen.StartCap=pen.EndCap=LineCap.Round;using(GraphicsPath left=UiShape.Rounded(new Rectangle(14,24,17,16),5))graphics.DrawPath(pen,left);using(GraphicsPath right=UiShape.Rounded(new Rectangle(33,24,17,16),5))graphics.DrawPath(pen,right);graphics.DrawLine(pen,27,32,37,32);}graphics.Restore(saved);}
}
internal sealed class BrandMark:Control {
 public BrandMark(){SetStyle(ControlStyles.UserPaint|ControlStyles.AllPaintingInWmPaint|ControlStyles.OptimizedDoubleBuffer|ControlStyles.ResizeRedraw,true);}
 protected override void OnPaint(PaintEventArgs e){using(SolidBrush b=new SolidBrush(Parent==null?Color.White:Parent.BackColor))e.Graphics.FillRectangle(b,ClientRectangle);BlueGlyph.Brand(e.Graphics,new Rectangle(0,0,Math.Min(Width,Height),Math.Min(Width,Height)));}
}
internal sealed class StatePill:Control {
 public Color Dot=Color.FromArgb(250,165,83);
 public StatePill(){SetStyle(ControlStyles.UserPaint|ControlStyles.AllPaintingInWmPaint|ControlStyles.OptimizedDoubleBuffer|ControlStyles.ResizeRedraw,true);Font=new Font("Segoe UI",9,FontStyle.Bold);}
 protected override void OnTextChanged(EventArgs e){base.OnTextChanged(e);Invalidate();}
 protected override void OnPaint(PaintEventArgs e){BluePalette.Backdrop(e.Graphics,this);e.Graphics.SmoothingMode=SmoothingMode.AntiAlias;int h=Height-1;using(GraphicsPath path=UiShape.Rounded(new Rectangle(0,0,Width-1,h),Math.Max(4,h/2)))using(SolidBrush b=new SolidBrush(Color.White))e.Graphics.FillPath(b,path);int d=Math.Max(6,Height/5);using(SolidBrush b=new SolidBrush(Dot))e.Graphics.FillEllipse(b,Height/2-d/2,Height/2-d/2,d,d);TextRenderer.DrawText(e.Graphics,Text,Font,new Rectangle(Height-2,0,Math.Max(1,Width-Height-8),Height),Color.FromArgb(84,107,145),TextFormatFlags.VerticalCenter|TextFormatFlags.EndEllipsis);}
}
internal sealed class HeroIllustration:Control {
 public HeroIllustration(){SetStyle(ControlStyles.UserPaint|ControlStyles.AllPaintingInWmPaint|ControlStyles.OptimizedDoubleBuffer|ControlStyles.ResizeRedraw,true);TabStop=false;AccessibleRole=AccessibleRole.Graphic;AccessibleName="Компьютер и телефон, соединённые кабелем";}
 protected override void OnPaint(PaintEventArgs e){
  BluePalette.Backdrop(e.Graphics,this);Graphics g=e.Graphics;g.SmoothingMode=SmoothingMode.AntiAlias;GraphicsState original=g.Save();float scale=Math.Min(Width/270f,Height/270f);g.TranslateTransform((Width-270*scale)/2,(Height-270*scale)/2);g.ScaleTransform(scale,scale);
  using(Pen orbit=new Pen(Color.FromArgb(202,217,254),1)){orbit.DashStyle=DashStyle.Dash;g.DrawEllipse(orbit,29,12,223,223);}using(SolidBrush dot=new SolidBrush(Color.FromArgb(206,221,255)))for(int x=110;x<255;x+=17)for(int y=30;y<104;y+=17)g.FillEllipse(dot,x,y,2,2);
  GraphicsState laptop=g.Save();g.TranslateTransform(20,135);g.RotateTransform(-12);using(SolidBrush b=new SolidBrush(Color.FromArgb(189,208,246)))using(GraphicsPath p=UiShape.Rounded(new Rectangle(0,0,163,103),6))g.FillPath(b,p);using(SolidBrush b=new SolidBrush(Color.FromArgb(218,230,255)))g.FillRectangle(b,7,7,149,87);using(Pen line=new Pen(Color.White,1.3f))g.DrawRectangle(line,7,7,149,87);using(Pen shine=new Pen(Color.FromArgb(235,242,255),3))g.DrawLine(shine,45,75,111,21);using(SolidBrush b=new SolidBrush(Color.FromArgb(172,192,229)))g.FillPolygon(b,new Point[]{new Point(-12,105),new Point(176,105),new Point(162,117),new Point(1,117)});g.Restore(laptop);
  GraphicsState phone=g.Save();g.TranslateTransform(166,78);g.RotateTransform(11);using(SolidBrush shadow=new SolidBrush(Color.FromArgb(27,54,97,174)))using(GraphicsPath p=UiShape.Rounded(new Rectangle(6,11,95,189),22))g.FillPath(shadow,p);using(SolidBrush body=new SolidBrush(Color.FromArgb(36,58,99)))using(GraphicsPath p=UiShape.Rounded(new Rectangle(0,0,95,189),22))g.FillPath(body,p);using(SolidBrush edge=new SolidBrush(Color.FromArgb(23,40,72)))using(GraphicsPath p=UiShape.Rounded(new Rectangle(5,5,85,179),18))g.FillPath(edge,p);using(LinearGradientBrush screen=new LinearGradientBrush(new Rectangle(10,10,75,169),Color.FromArgb(219,230,255),Color.FromArgb(190,209,255),90))using(GraphicsPath p=UiShape.Rounded(new Rectangle(10,10,75,169),15))g.FillPath(screen,p);using(SolidBrush notch=new SolidBrush(Color.FromArgb(23,40,72)))using(GraphicsPath p=UiShape.Rounded(new Rectangle(28,7,38,12),6))g.FillPath(notch,p);using(SolidBrush tile=new SolidBrush(Color.FromArgb(235,241,255)))using(GraphicsPath p=UiShape.Rounded(new Rectangle(23,77,51,52),15))g.FillPath(tile,p);BlueGlyph.Draw(g,"chain",new RectangleF(34,90,30,28),BluePalette.Accent);g.Restore(phone);
  using(SolidBrush shadow=new SolidBrush(Color.FromArgb(15,62,91,153)))g.FillEllipse(shadow,46,43,45,45);using(SolidBrush tile=new SolidBrush(Color.White))g.FillEllipse(tile,43,38,45,45);BlueGlyph.Draw(g,"wifi",new RectangleF(54,50,23,23),BluePalette.Accent);using(SolidBrush white=new SolidBrush(Color.White))g.FillEllipse(white,226,230,30,30);using(Pen check=new Pen(BluePalette.Accent,2.3f)){check.StartCap=check.EndCap=LineCap.Round;g.DrawLines(check,new Point[]{new Point(236,245),new Point(240,249),new Point(247,241)});}g.Restore(original);
 }
}
internal sealed class ModernComboBox:ComboBox {
 internal bool AdapterChoices;
 PopupFrame popup;bool joined,below=true;int plannedHeight;readonly ToolTip fullName=new ToolTip();
 static int Pixels(float value,float scale){return Math.Max(0,(int)Math.Round(value*scale));}
 static float DesktopScale(){using(Graphics graphics=Graphics.FromHwnd(IntPtr.Zero))return graphics.DpiX/96f;}
 public ModernComboBox(){DropDownStyle=ComboBoxStyle.DropDownList;DrawMode=DrawMode.OwnerDrawFixed;FlatStyle=FlatStyle.Flat;float scale=DesktopScale();ItemHeight=Pixels(38,scale);IntegralHeight=false;DropDownHeight=Pixels(250,scale);Font=new Font("Segoe UI",10);}
 internal Rectangle PopupLimit(){Form form=FindForm();Rectangle screen=Screen.FromControl(this).WorkingArea;return form==null?screen:Rectangle.Intersect(form.RectangleToScreen(form.ClientRectangle),screen);}
 Rectangle FieldScreen(){NativeMethods.Rect rect;return NativeMethods.GetWindowRect(Handle,out rect)?Rectangle.FromLTRB(rect.Left,rect.Top,rect.Right,rect.Bottom):RectangleToScreen(ClientRectangle);}
 protected override void OnDropDown(EventArgs e){SizeNativePopup();base.OnDropDown(e);ShapeNativePopup();if(IsHandleCreated&&!IsDisposed)BeginInvoke((MethodInvoker)delegate{if(!IsDisposed&&IsHandleCreated&&DroppedDown)ShapeNativePopup();});}
 protected override void OnDropDownClosed(EventArgs e){JoinPopup(false,true);base.OnDropDownClosed(e);}
 void SizeNativePopup(){Rectangle field=FieldScreen(),limit=PopupLimit();int room=Math.Max(limit.Bottom-field.Bottom+1,field.Top-limit.Top+1),item=Math.Max(1,ItemHeight);int maxRows=Math.Max(1,Pixels(250,DesktopScale())/item),roomRows=Math.Max(1,room/item);int rows=Math.Min(Math.Max(1,Items.Count),Math.Min(maxRows,roomRows));plannedHeight=rows*item;DropDownWidth=Width;DropDownHeight=plannedHeight+2;}
 void ShapeNativePopup(){NativeMethods.ComboInfo info=new NativeMethods.ComboInfo();info.Size=Marshal.SizeOf(typeof(NativeMethods.ComboInfo));if(!NativeMethods.GetComboBoxInfo(Handle,ref info)||info.List==IntPtr.Zero)return;if(popup==null)popup=new PopupFrame(this);popup.Attach(info.List);popup.RoundWindow(true);}
 void JoinPopup(bool active,bool opensBelow){if(joined==active&&below==opensBelow)return;joined=active;below=opensBelow;UpdateFieldRegion();Invalidate();}
 protected override void OnHandleCreated(EventArgs e){base.OnHandleCreated(e);UpdateFieldRegion();}
 protected override void OnHandleDestroyed(EventArgs e){if(popup!=null){popup.ReleaseHandle();popup=null;}joined=false;base.OnHandleDestroyed(e);}
 protected override void Dispose(bool disposing){if(disposing)fullName.Dispose();base.Dispose(disposing);}
 protected override void OnSelectedIndexChanged(EventArgs e){base.OnSelectedIndexChanged(e);if(fullName!=null)fullName.SetToolTip(this,SelectedItem==null?"":SelectedItem.ToString());}
 // When attached, omit the shared edge and round only the two outer corners.
 static GraphicsPath OpenOutline(Rectangle box,int radius,bool roundTop){int r=Math.Max(1,Math.Min(radius,Math.Min(box.Width,box.Height)/2)),d=r*2;GraphicsPath path=new GraphicsPath();if(roundTop){path.AddLine(box.Left,box.Bottom,box.Left,box.Top+r);path.AddArc(box.Left,box.Top,d,d,180,90);path.AddLine(box.Left+r,box.Top,box.Right-r,box.Top);path.AddArc(box.Right-d,box.Top,d,d,270,90);path.AddLine(box.Right,box.Top+r,box.Right,box.Bottom);}else{path.AddLine(box.Left,box.Top,box.Left,box.Bottom-r);path.AddArc(box.Left,box.Bottom-d,d,d,180,-90);path.AddLine(box.Left+r,box.Bottom,box.Right-r,box.Bottom);path.AddArc(box.Right-d,box.Bottom-d,d,d,90,-90);path.AddLine(box.Right,box.Bottom-r,box.Right,box.Top);}return path;}
 void UpdateFieldRegion(){if(Width<3||Height<3)return;int radius=Pixels(11,DesktopScale());using(GraphicsPath path=joined?OpenOutline(new Rectangle(0,0,Width,Height),radius,below):UiShape.Rounded(new Rectangle(0,0,Width,Height),radius)){path.CloseFigure();Region old=Region;Region=new Region(path);if(old!=null)old.Dispose();}}
 protected override void OnSizeChanged(EventArgs e){base.OnSizeChanged(e);UpdateFieldRegion();}
 protected override void OnDrawItem(DrawItemEventArgs e){if(e.Index<0)return;DrawChoice(e.Graphics,e.Bounds,e.Index,(e.State&DrawItemState.Selected)!=0,(e.State&DrawItemState.ComboBoxEdit)!=0);}
 static string FitName(Graphics graphics,string text,Font font,int width){if(TextRenderer.MeasureText(graphics,text,font,Size.Empty,TextFormatFlags.SingleLine|TextFormatFlags.NoPrefix).Width<=width)return text;int tail=Math.Min(12,text.Length/2);while(tail>1&&TextRenderer.MeasureText(graphics,"…"+text.Substring(text.Length-tail),font,Size.Empty,TextFormatFlags.SingleLine|TextFormatFlags.NoPrefix).Width>width)tail--;string end="…"+text.Substring(text.Length-tail);int low=0,high=Math.Max(0,text.Length-tail);while(low<high){int middle=(low+high+1)/2;if(TextRenderer.MeasureText(graphics,text.Substring(0,middle)+end,font,Size.Empty,TextFormatFlags.SingleLine|TextFormatFlags.NoPrefix).Width<=width)low=middle;else high=middle-1;}return text.Substring(0,low)+end;}
 void DrawChoice(Graphics g,Rectangle bounds,int index,bool selected,bool closed){
  float scale=g.DpiX/96f;g.SmoothingMode=SmoothingMode.AntiAlias;using(SolidBrush brush=new SolidBrush(closed&&!joined?Color.FromArgb(248,250,255):Color.White))g.FillRectangle(brush,bounds);if(selected&&!closed){Rectangle highlight=Rectangle.Inflate(bounds,-Pixels(6,scale),-Pixels(3,scale));if(highlight.Width>0&&highlight.Height>0)using(GraphicsPath path=UiShape.Rounded(highlight,Pixels(9,scale)))using(SolidBrush brush=new SolidBrush(SystemInformation.HighContrast?SystemColors.Highlight:BluePalette.Pale))g.FillPath(brush,path);}if(index<0||index>=Items.Count)return;
  string title=Items[index].ToString(),subtitle="";Adapter adapter=Items[index] as Adapter;if(AdapterChoices){if(index==0&&adapter==null){title="Автоматически";subtitle="Рекомендуется";}else if(adapter!=null){title=adapter.Name;subtitle=adapter.Description??"";}}
  int pad=Math.Max(Pixels(8,scale),bounds.Height/5),iconSize=AdapterChoices?Math.Min(Pixels(28,scale),bounds.Height-Pixels(10,scale)):0,x=bounds.X+pad+(closed?0:Pixels(5,scale));
  if(AdapterChoices){using(SolidBrush iconBack=new SolidBrush(Color.FromArgb(229,237,255)))using(GraphicsPath path=UiShape.Rounded(new Rectangle(x,bounds.Y+(bounds.Height-iconSize)/2,iconSize,iconSize),Pixels(8,scale)))g.FillPath(iconBack,path);BlueGlyph.Draw(g,index==0?"sparkle":"globe",new RectangleF(x+Pixels(5,scale),bounds.Y+(bounds.Height-iconSize)/2+Pixels(5,scale),iconSize-Pixels(10,scale),iconSize-Pixels(10,scale)),BluePalette.Accent);x+=iconSize+Pixels(10,scale);}
  Rectangle text=new Rectangle(x,bounds.Y+Pixels(2,scale),Math.Max(1,bounds.Right-x-Pixels(closed?12:32,scale)),bounds.Height-Pixels(4,scale));Color foreground=SystemInformation.HighContrast&&selected&&!closed?SystemColors.HighlightText:Enabled?Color.FromArgb(52,75,112):BluePalette.Muted;TextFormatFlags flags=TextFormatFlags.EndEllipsis|TextFormatFlags.VerticalCenter|TextFormatFlags.NoPrefix;
  if(subtitle.Length!=0){using(Font heading=new Font(Font.FontFamily,Font.Size,FontStyle.Bold))TextRenderer.DrawText(g,FitName(g,title,heading,text.Width),heading,new Rectangle(text.X,text.Y+Pixels(3,scale),text.Width,text.Height/2),foreground,flags);using(Font sub=new Font(Font.FontFamily,Math.Max(8,Font.Size-1)))TextRenderer.DrawText(g,subtitle,sub,new Rectangle(text.X,text.Y+text.Height/2,text.Width,text.Height/2-1),SystemInformation.HighContrast&&selected&&!closed?SystemColors.HighlightText:BluePalette.Muted,flags);}else TextRenderer.DrawText(g,FitName(g,title,Font,text.Width),Font,text,foreground,flags);
  if(!closed&&index==SelectedIndex){int right=bounds.Right-Pixels(17,scale),middle=bounds.Y+bounds.Height/2;using(Pen check=new Pen(BluePalette.Accent,1.7f*scale)){check.StartCap=check.EndCap=LineCap.Round;g.DrawLines(check,new Point[]{new Point(right-Pixels(4,scale),middle),new Point(right-Pixels(1,scale),middle+Pixels(3,scale)),new Point(right+Pixels(5,scale),middle-Pixels(4,scale))});}}
 }
 protected override void WndProc(ref Message message){base.WndProc(ref message);if(!IsHandleCreated)return;if(message.Msg==0x000F){using(Graphics graphics=Graphics.FromHwnd(Handle))PaintClosed(graphics);}else if((message.Msg==0x0317||message.Msg==0x0318)&&message.WParam!=IntPtr.Zero){using(Graphics graphics=Graphics.FromHdc(message.WParam))PaintClosed(graphics);}}
 void PaintClosed(Graphics graphics){float scale=graphics.DpiX/96f;graphics.SmoothingMode=SmoothingMode.AntiAlias;using(SolidBrush behind=new SolidBrush(Parent is CardPanel?((CardPanel)Parent).Fill:Parent==null?Color.White:Parent.BackColor))graphics.FillRectangle(behind,ClientRectangle);Rectangle box=new Rectangle(0,0,Width-1,Height-1);using(GraphicsPath outline=joined?OpenOutline(box,Pixels(11,scale),below):UiShape.Rounded(box,Pixels(11,scale))){using(GraphicsPath fill=(GraphicsPath)outline.Clone()){fill.CloseFigure();using(SolidBrush brush=new SolidBrush(joined?Color.White:Color.FromArgb(248,250,255)))graphics.FillPath(brush,fill);}using(Pen line=new Pen(joined||Focused?Color.FromArgb(135,166,255):BluePalette.Line,scale))graphics.DrawPath(line,outline);}DrawChoice(graphics,new Rectangle(Pixels(3,scale),Pixels(3,scale),Math.Max(1,Width-Pixels(29,scale)),Math.Max(1,Height-Pixels(6,scale))),SelectedIndex,false,true);using(Pen arrow=new Pen(Enabled?Color.FromArgb(119,142,181):BluePalette.Muted,1.6f*scale)){int x=Width-Pixels(17,scale),y=Height/2;graphics.DrawLines(arrow,new Point[]{new Point(x-Pixels(4,scale),y-Pixels(2,scale)),new Point(x,y+Pixels(2,scale)),new Point(x+Pixels(4,scale),y-Pixels(2,scale))});}}
 // Keep ComboLBox's native input/scroll/UIA; customize only this window.
 sealed class PopupFrame:NativeWindow {
  readonly ModernComboBox owner;bool shaping,lastBelow=true;int lastWidth,lastHeight;
  internal PopupFrame(ModernComboBox source){owner=source;}
  internal void Attach(IntPtr list){if(Handle==list)return;if(Handle!=IntPtr.Zero)ReleaseHandle();AssignHandle(list);lastWidth=lastHeight=0;try{int policy=1;NativeMethods.DwmSetWindowAttribute(Handle,2,ref policy,4);}catch(DllNotFoundException){}catch(EntryPointNotFoundException){}}
  internal void RoundWindow(bool force){if(shaping||Handle==IntPtr.Zero||owner.IsDisposed)return;shaping=true;try{
   long style=NativeMethods.GetStyle(Handle,-16),extended=NativeMethods.GetStyle(Handle,-20);bool frame=false;if((style&0x00800000L)!=0){NativeMethods.SetStyle(Handle,-16,style&~0x00800000L);frame=true;}if((extended&0x00000200L)!=0){NativeMethods.SetStyle(Handle,-20,extended&~0x00000200L);frame=true;}
   Rectangle field=owner.FieldScreen(),limit=owner.PopupLimit();int down=Math.Max(0,limit.Bottom-field.Bottom+1),up=Math.Max(0,field.Top-limit.Top+1),desired=Math.Max(1,owner.plannedHeight);bool opensBelow=down>=desired||up<desired&&down>=up;int room=opensBelow?down:up,item=Math.Max(1,owner.ItemHeight);int height=Math.Min(desired,Math.Max(1,room/item)*item);height=Math.Min(height,room);int width=Math.Min(field.Width,limit.Width);if(width<3||height<3)return;
   int x=Math.Max(limit.Left,Math.Min(field.Left,limit.Right-width)),y=opensBelow?field.Bottom-1:field.Top-height+1;y=Math.Max(limit.Top,Math.Min(y,limit.Bottom-height));NativeMethods.Rect current;if(!NativeMethods.GetWindowRect(Handle,out current))return;
   if(frame||current.Left!=x||current.Top!=y||current.Right-current.Left!=width||current.Bottom-current.Top!=height)NativeMethods.SetWindowPos(Handle,IntPtr.Zero,x,y,width,height,0x0014u|(frame?0x0020u:0));
   owner.JoinPopup(true,opensBelow);if(!force&&lastWidth==width&&lastHeight==height&&lastBelow==opensBelow)return;
   using(Graphics graphics=Graphics.FromHwnd(Handle))using(GraphicsPath path=OpenOutline(new Rectangle(0,0,width,height),Pixels(11,graphics.DpiX/96f),!opensBelow)){path.CloseFigure();using(Region shape=new Region(path)){IntPtr region=shape.GetHrgn(graphics);if(NativeMethods.SetWindowRgn(Handle,region,true)==0)NativeMethods.DeleteObject(region);else{lastWidth=width;lastHeight=height;lastBelow=opensBelow;}}}
  }finally{shaping=false;}}
  void DrawBorder(){if(shaping||Handle==IntPtr.Zero||!NativeMethods.IsWindowVisible(Handle))return;NativeMethods.Rect rect;if(!NativeMethods.GetWindowRect(Handle,out rect))return;IntPtr dc=NativeMethods.GetWindowDC(Handle);if(dc==IntPtr.Zero)return;try{using(Graphics graphics=Graphics.FromHdc(dc)){graphics.SmoothingMode=SmoothingMode.AntiAlias;float scale=graphics.DpiX/96f;Rectangle bounds=new Rectangle(0,0,Math.Max(1,rect.Right-rect.Left-1),Math.Max(1,rect.Bottom-rect.Top-1));using(GraphicsPath path=OpenOutline(bounds,Pixels(11,scale),!owner.below))using(Pen line=new Pen(Color.FromArgb(135,166,255),scale))graphics.DrawPath(line,path);}}finally{NativeMethods.ReleaseDC(Handle,dc);}}
  protected override void WndProc(ref Message message){base.WndProc(ref message);if(message.Msg==0x0047||message.Msg==0x0005)RoundWindow(false);if(message.Msg==0x000F||message.Msg==0x0085)DrawBorder();}
 }
 static class NativeMethods {
  [StructLayout(LayoutKind.Sequential)]internal struct Rect {internal int Left,Top,Right,Bottom;}
  [StructLayout(LayoutKind.Sequential)]internal struct ComboInfo {internal int Size;internal Rect Item,Button;internal int ButtonState;internal IntPtr Combo,Edit,List;}
  [DllImport("user32.dll")]internal static extern bool GetComboBoxInfo(IntPtr combo,ref ComboInfo info);
  [DllImport("user32.dll")]internal static extern bool GetWindowRect(IntPtr window,out Rect rect);
  [DllImport("user32.dll")]internal static extern bool IsWindowVisible(IntPtr window);
  [DllImport("user32.dll")]internal static extern IntPtr GetWindowDC(IntPtr window);
  [DllImport("user32.dll")]internal static extern int ReleaseDC(IntPtr window,IntPtr dc);
  [DllImport("gdi32.dll")]internal static extern bool DeleteObject(IntPtr value);
  [DllImport("user32.dll")]internal static extern int SetWindowRgn(IntPtr window,IntPtr region,bool redraw);
  [DllImport("user32.dll")]internal static extern bool SetWindowPos(IntPtr window,IntPtr after,int x,int y,int width,int height,uint flags);
  // Vista+/Win7 OS component; policy attribute2 + value1 disables non-client
  // rendering for this popup only. Composition-disabled systems fall back.
  [DllImport("dwmapi.dll",PreserveSig=true)]internal static extern int DwmSetWindowAttribute(IntPtr window,int attribute,ref int value,int size);
  [DllImport("user32.dll",EntryPoint="GetWindowLongW")]static extern int GetWindowLong32(IntPtr window,int index);
  [DllImport("user32.dll",EntryPoint="SetWindowLongW")]static extern int SetWindowLong32(IntPtr window,int index,int value);
  [DllImport("user32.dll",EntryPoint="GetWindowLongPtrW")]static extern IntPtr GetWindowLong64(IntPtr window,int index);
  [DllImport("user32.dll",EntryPoint="SetWindowLongPtrW")]static extern IntPtr SetWindowLong64(IntPtr window,int index,IntPtr value);
  internal static long GetStyle(IntPtr window,int index){return IntPtr.Size==8?GetWindowLong64(window,index).ToInt64():GetWindowLong32(window,index);}
  internal static void SetStyle(IntPtr window,int index,long value){if(IntPtr.Size==8)SetWindowLong64(window,index,new IntPtr(value));else SetWindowLong32(window,index,unchecked((int)value));}
 }
}

}
