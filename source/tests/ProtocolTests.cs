using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Collections.Generic;
using System.Threading;
using UsbBridge;

// These tests exercise the production transport classes. MIDI hardware is not emulated.
sealed class ProtocolTests {
    static int passed;
    static void Check(bool condition, string message) { if (!condition) throw new Exception(message); }
    static void Pass(string name) { Console.WriteLine("PASS " + name); passed++; }
    static void Rejected(ThreadStart action, string name) {
        bool rejected = false;
        try { action(); } catch (IOException) { rejected = true; }
        Check(rejected, "Accepted " + name);
        Pass(name);
    }

    sealed class FragmentStream : Stream {
        readonly Stream stream;
        public bool Corrupt;
        public byte[] Last;
        public FragmentStream(Stream stream) { this.stream = stream; }
        public override void Write(byte[] bytes, int offset, int count) {
            byte[] copy = new byte[count]; Buffer.BlockCopy(bytes, offset, copy, 0, count);
            Last = (byte[])copy.Clone();
            if (Corrupt && count > 40) copy[30] ^= 1;
            for (int p = 0; p < count; p += 7) stream.Write(copy, p, Math.Min(7, count - p));
        }
        public override int Read(byte[] bytes, int offset, int count) { return stream.Read(bytes, offset, Math.Min(count, 3)); }
        protected override void Dispose(bool disposing) { stream.Close(); }
        public override bool CanRead { get { return true; } }
        public override bool CanWrite { get { return true; } }
        public override bool CanSeek { get { return false; } }
        public override long Length { get { throw new NotSupportedException(); } }
        public override long Position { get { throw new NotSupportedException(); } set { throw new NotSupportedException(); } }
        public override void Flush() { stream.Flush(); }
        public override long Seek(long o, SeekOrigin s) { throw new NotSupportedException(); }
        public override void SetLength(long l) { throw new NotSupportedException(); }
    }

    sealed class Pair : IDisposable {
        public SecureWire Client, Server;
        public FragmentStream ClientStream;
        readonly TcpClient clientSocket, serverSocket;
        public Pair(string clientCode, string serverCode) {
            TcpListener listener = new TcpListener(IPAddress.Loopback, 0); listener.Start();
            clientSocket = new TcpClient(); clientSocket.Connect((IPEndPoint)listener.LocalEndpoint);
            serverSocket = listener.AcceptTcpClient(); listener.Stop();
            clientSocket.NoDelay = serverSocket.NoDelay = true;
            clientSocket.ReceiveTimeout = serverSocket.ReceiveTimeout = 5000;
            Exception failure = null;
            ManualResetEvent completed = new ManualResetEvent(false);
            Wire.Run(delegate {
                try { Server = new SecureWire(new FragmentStream(serverSocket.GetStream()), serverCode, false); }
                catch (Exception ex) { failure = ex; }
                finally { completed.Set(); }
            });
            try {
                ClientStream = new FragmentStream(clientSocket.GetStream());
                Client = new SecureWire(ClientStream, clientCode, true);
                Check(completed.WaitOne(6000, false), "Handshake timeout");
                if (failure != null) throw failure;
            } catch { Dispose(); completed.WaitOne(6000, false); throw; }
            finally { completed.Close(); }
        }
        public void Dispose() { clientSocket.Close(); serverSocket.Close(); if(Client!=null)Client.Dispose();if(Server!=null)Server.Dispose(); }
    }

    sealed class PhoneMidi {
        public MidiPipe Pc;
        public int Session, Expected = 1, Acks, DataAttempts, Hellos, Closes;
        public bool DropFirstAck, DropAllAcks, FailClose;
        public byte[] LastClose;
        public ThreadStart AfterHelloAck, OnClose;
        public ManualResetEvent DataSent;
        public readonly List<byte> Received = new List<byte>();
        public static byte[] Decode(byte[] encoded) {
            Check(encoded[0] == 0xf0 && encoded[encoded.Length - 1] == 0xf7, "SysEx bounds");
            Check(encoded[1] == 0x7d && encoded[2] == 0x55 && encoded[3] == 0x42, "SysEx ID");
            List<byte> result = new List<byte>();
            for (int p = 4; p < encoded.Length - 1;) {
                byte mask = encoded[p++];
                for (int j = 0; j < 7 && p < encoded.Length - 1; j++) {
                    Check(encoded[p] < 128, "Not MIDI-safe");
                    result.Add((byte)(encoded[p++] | ((mask >> j & 1) << 7)));
                }
            }
            return result.ToArray();
        }
        public void FromPc(byte[] encoded) {
            byte[] raw = Decode(encoded);
            Check(unchecked((uint)Wire.Int(raw, raw.Length - 4)) == MidiPipe.Crc(raw, raw.Length - 4), "CRC encoding");
            int type = raw[0], sn = Wire.Int(raw, 5);
            if (type == 2) { Hellos++; Session = Wire.Int(raw, 1); Feed(3, 0, new byte[0], false, false); if (AfterHelloAck != null) AfterHelloAck(); return; }
            if (type == 1) { Acks++; return; }
            if (type == 4) {
                Check(raw.Length == 13 && sn == 0 && Wire.Int(raw, 1) == Session, "Invalid scoped CLOSE");
                Closes++; LastClose = (byte[])encoded.Clone();
                if (OnClose != null) OnClose();
                if (FailClose) throw new IOException("MIDI removed while sending CLOSE");
                return;
            }
            Check(type == 0, "Unexpected PC MIDI message");
            DataAttempts++;
            if (sn == Expected) {
                for (int p = 9; p < raw.Length - 4; p++) Received.Add(raw[p]);
                Expected++;
            } else Check(sn == Expected - 1, "Nonsequential write");
            if (DataSent != null) DataSent.Set();
            if (DropAllAcks) return;
            if (DropFirstAck) { DropFirstAck = false; return; }
            Feed(1, sn, new byte[0], false, false);
        }
        public void Feed(int type, int sequence, byte[] payload, bool corrupt, bool wrongSession) {
            byte[] raw = new byte[13 + payload.Length]; raw[0] = (byte)type;
            Wire.Put(raw, 1, Session + (wrongSession ? 1 : 0)); Wire.Put(raw, 5, sequence);
            Buffer.BlockCopy(payload, 0, raw, 9, payload.Length);
            Wire.Put(raw, raw.Length - 4, (int)MidiPipe.Crc(raw, raw.Length - 4));
            if (corrupt) raw[raw.Length - 1] ^= 1;
            byte[] encoded = MidiPipe.Encode(raw);
            // Split across arbitrary USB deliveries and interleave legal MIDI realtime bytes.
            for (int p = 0; p < encoded.Length; p += 5) {
                int n = Math.Min(5, encoded.Length - p);
                byte[] chunk = new byte[n + 1]; chunk[0] = 0xf8;
                Buffer.BlockCopy(encoded, p, chunk, 1, n); Pc.Feed(chunk);
            }
        }
    }

    static void Main() {
        try {
            using (Pair p = new Pair("TESTCODE2345", "TESTCODE2345")) {
                byte[] message = Wire.Random(8192); p.Client.Send(message);
                Check(Wire.Equal(message, p.Server.Receive()), "Fragmented encrypted stream");
                p.Server.Send(new byte[0]); Check(p.Client.Receive().Length == 0, "Empty record");
                Pass("encrypted records survive transport fragmentation");
            }
            using(Pair p=new Pair("TESTCODE2345","TESTCODE2345")){
                byte[] message=Wire.Random(64);p.Client.Send(message);byte[] first=p.ClientStream.Last;p.Server.Receive();p.Client.Send(message);byte[] second=p.ClientStream.Last;Check(Wire.Equal(message,p.Server.Receive()),"Repeated plaintext failed");bool different=false;for(int i=8;i<24;i++)different|=first[i]!=second[i];Check(different,"IV reused between records");Pass("reused cipher objects still use fresh cryptographic IV per record");
                for(int i=0;i<32;i++){byte[] a=Wire.Random(65+i),b=Wire.Random(129+i);p.Client.Send(a);p.Server.Send(b);Check(Wire.Equal(a,p.Server.Receive())&&Wire.Equal(b,p.Client.Receive()),"Directional state leaked");}Pass("reused cipher and HMAC state stay independent across directions and records");
            }
            using(Pair p=new Pair("TESTCODE2345","TESTCODE2345")){
                ManualResetEvent entered=new ManualResetEvent(false),finished=new ManualResetEvent(false);bool rejected=false;Wire.Run(delegate{entered.Set();try{p.Client.Receive();}catch(IOException){rejected=true;}finally{finished.Set();}});entered.WaitOne(1000,false);Thread.Sleep(30);System.Diagnostics.Stopwatch close=System.Diagnostics.Stopwatch.StartNew();p.Client.Dispose();Check(finished.WaitOne(1000,false)&&rejected&&close.ElapsedMilliseconds<1500,"Dispose waited for blocked receive lock");entered.Close();finished.Close();Pass("disposing reused crypto closes socket before waiting for reader state");
            }
            Rejected(delegate { using (Pair p = new Pair("WRONGCODE234", "TESTCODE2345")) {} }, "wrong pairing code rejected");
            using (Pair p = new Pair("TESTCODE2345", "TESTCODE2345")) {
                p.ClientStream.Corrupt = true; p.Client.Send(Wire.Random(200));
                Rejected(delegate { p.Server.Receive(); }, "modified encrypted record rejected");
            }
            using (Pair p = new Pair("TESTCODE2345", "TESTCODE2345")) {
                p.Client.Send(new byte[] { 1, 2, 3 }); byte[] record = p.ClientStream.Last;
                p.Server.Receive(); p.ClientStream.Write(record, 0, record.Length);
                Rejected(delegate { p.Server.Receive(); }, "replayed record rejected");
            }
            using (Pair p = new Pair("TESTCODE2345", "TESTCODE2345")) {
                p.ClientStream.Write(new byte[] { 0x7f, 0xff, 0xff, 0xff }, 0, 4);
                Rejected(delegate { p.Server.Receive(); }, "oversized record rejected before allocation");
            }
            using(AutoResetEvent completion=new AutoResetEvent(false)){
                MidiDevice.AwaitOutput(completion,delegate{return true;},delegate{return false;},500);
                Pass("synchronous MIDI completion never waits for a callback event");
                using(ManualResetEvent entered=new ManualResetEvent(false))using(ManualResetEvent finished=new ManualResetEvent(false)){
                    int nativeDone=0;Exception outputError=null;
                    Wire.Run(delegate{try{MidiDevice.AwaitOutput(completion,delegate{entered.Set();return Thread.VolatileRead(ref nativeDone)==1;},delegate{return false;},2000);}catch(Exception error){outputError=error;}finally{finished.Set();}});
                    Check(entered.WaitOne(500,false),"MIDI wait never started");completion.Set();Check(!finished.WaitOne(30,false),"Spurious event completed a live native buffer");Interlocked.Exchange(ref nativeDone,1);completion.Set();
                    Check(finished.WaitOne(500,false)&&outputError==null,"MIDI completion did not wake writer");Pass("MIDI event wakes promptly but spurious event never frees an unfinished buffer");
                }
                using(ManualResetEvent entered=new ManualResetEvent(false))using(ManualResetEvent finished=new ManualResetEvent(false)){
                    int canceled=0;bool closed=false;
                    Wire.Run(delegate{try{MidiDevice.AwaitOutput(completion,delegate{entered.Set();return false;},delegate{return Thread.VolatileRead(ref canceled)==1;},4000);}catch(IOException error){closed=error.Message=="MIDI device closed";}finally{finished.Set();}});
                    Check(entered.WaitOne(500,false),"Canceled MIDI wait never started");Interlocked.Exchange(ref canceled,1);completion.Set();Check(finished.WaitOne(500,false)&&closed,"MIDI cancellation waited for driver timeout");Pass("MIDI cancellation wakes completion wait without waiting four seconds");
                }
                Rejected(delegate{MidiDevice.AwaitOutput(completion,delegate{return false;},delegate{return false;},30);},"missing MIDI completion has a bounded monotonic deadline");
            }
            PhoneMidi phone = new PhoneMidi();
            using (MidiPipe pc = new MidiPipe(phone.FromPc, null)) {
                phone.Pc = pc; pc.Connect(); phone.DropFirstAck = true;
                byte[] payload = new byte[2307]; for (int i = 0; i < payload.Length; i++) payload[i] = (byte)i;
                pc.Write(payload, 0, payload.Length);
                Check(Wire.Equal(payload, phone.Received.ToArray()), "Retransmission changed bytes");
                Check(pc.Retries == 1 && phone.DataAttempts == 5, "Lost ACK not retried once");
                Pass("MIDI write retries a lost ACK without duplicate payload");
                phone.Feed(0, 1, new byte[] { 10 }, true, false);
                phone.Feed(0, 1, new byte[] { 11 }, false, true);
                phone.Feed(0, 2, new byte[] { 12 }, false, false);
                phone.Feed(0, 1, new byte[] { 21, 22 }, false, false);
                phone.Feed(0, 1, new byte[] { 21, 22 }, false, false);
                phone.Feed(0, 2, new byte[] { 23 }, false, false);
                Check(Wire.Equal(Wire.Read(pc, 3), new byte[] { 21, 22, 23 }), "MIDI accepted invalid or duplicate data");
                Check(phone.Acks == 3, "Duplicate data not acknowledged");
                Pass("MIDI CRC/session/sequence checks, duplicate suppression and split SysEx");
                Check(pc.Read(new byte[0], 0, 0) == 0, "Zero-byte read blocked");
                ManualResetEvent closed = new ManualResetEvent(false);
                bool failed = false;
                Wire.Run(delegate { try { pc.ReadByte(); } catch (IOException) { failed = true; } finally { closed.Set(); } });
                pc.Close(); Check(closed.WaitOne(1000, false) && failed, "Close left reader blocked"); closed.Close();
                pc.Close(); Check(phone.Closes == 1, "Graceful close was not sent exactly once");
                Pass("MIDI disconnect unblocks reader");
            }
            PhoneMidi blockedPhone = new PhoneMidi();
            using (MidiPipe pc = new MidiPipe(blockedPhone.FromPc, null))
            using (MidiDevice device = new MidiDevice(pc))
            using (ManualResetEvent dataSent = new ManualResetEvent(false))
            using (ManualResetEvent readerDone = new ManualResetEvent(false))
            using (ManualResetEvent writerDone = new ManualResetEvent(false))
            using (ManualResetEvent disposeDone = new ManualResetEvent(false)) {
                blockedPhone.Pc = pc; pc.Connect(); blockedPhone.DropAllAcks = true; blockedPhone.DataSent = dataSent;
                bool readRejected = false, writeRejected = false;
                Wire.Run(delegate { try { pc.ReadByte(); } catch (IOException) { readRejected = true; } finally { readerDone.Set(); } });
                Wire.Run(delegate { try { pc.WriteByte(42); } catch (IOException) { writeRejected = true; } finally { writerDone.Set(); } });
                Check(dataSent.WaitOne(1000, false), "MIDI writer never entered ACK wait");
                object nativeWriteGate = typeof(MidiDevice).GetField("writeLock", System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Instance).GetValue(device);
                lock (nativeWriteGate) {
                    // Simulate an in-flight native send. The transport deadline
                    // must wake stream callers before native cleanup can proceed.
                    Wire.Run(delegate { try { device.Dispose(); } finally { disposeDone.Set(); } });
                    Check(readerDone.WaitOne(1000, false) && readRejected, "Direct device disposal left reader blocked");
                    Check(writerDone.WaitOne(1000, false) && writeRejected, "Direct device disposal left ACK writer blocked");
                    Check(!disposeDone.WaitOne(0, false), "Test failed to hold native cleanup gate");
                }
                Check(disposeDone.WaitOne(1000, false), "Native cleanup did not finish after gate release");
                Pass("direct MIDI device disposal wakes reader and ACK writer before native cleanup");
                Check(blockedPhone.Closes == 0, "Direct device disposal tried to send CLOSE");
                int attempts = blockedPhone.Hellos;
                Rejected(delegate { pc.Connect(); }, "aborted MIDI cannot send a new session hello");
                Check(blockedPhone.Hellos == attempts, "Aborted MIDI produced a new hello");
            }
            int cleanups = 0;
            MidiPipe cleanupPipe = new MidiPipe(delegate(byte[] ignored) { }, delegate { cleanups++; });
            cleanupPipe.Abort(); cleanupPipe.Close(); cleanupPipe.Close();
            Check(cleanups == 1, "MIDI cleanup callback repeated or recursed");
            Pass("MIDI abort and repeated close invoke cleanup once");
            PhoneMidi graceful = new PhoneMidi(); int gracefulCleanups = 0;
            MidiPipe gracefulPc = new MidiPipe(graceful.FromPc, delegate { gracefulCleanups++; });
            graceful.Pc = gracefulPc; gracefulPc.Connect(); gracefulPc.Close(); gracefulPc.Close();
            Check(graceful.Closes == 1 && gracefulCleanups == 1, "Graceful CLOSE or cleanup repeated");
            Pass("graceful MIDI close sends one session-scoped CLOSE and cleans up once");
            PhoneMidi next = new PhoneMidi();
            using (MidiPipe nextPc = new MidiPipe(next.FromPc, null)) {
                next.Pc = nextPc; nextPc.Connect();
                Check(next.Session != graceful.Session, "New MIDI connection reused the old session");
                nextPc.Feed(graceful.LastClose);
                next.Feed(4, 0, new byte[0], true, false);
                next.Feed(4, 0, new byte[0], false, true);
                next.Feed(4, 1, new byte[0], false, false);
                next.Feed(4, 0, new byte[] { 1 }, false, false);
                next.Feed(0, 1, new byte[] { 81 }, false, false);
                Check(nextPc.ReadByte() == 81, "Late or invalid CLOSE stopped a new MIDI session");
                nextPc.WriteByte(82);
                Check(next.Received.Count == 1 && next.Received[0] == 82, "New session could not send after late CLOSE");
                Pass("late old-session and malformed CLOSE cannot terminate a new connection");
                next.Feed(4, 0, new byte[0], false, false);
                Rejected(delegate { nextPc.ReadByte(); }, "matching remote CLOSE terminates the session");
                nextPc.Close();
                Check(next.Closes == 0, "Remote CLOSE was echoed");
                Pass("remote MIDI close is never echoed by disposal");
            }
            PhoneMidi abortedReady = new PhoneMidi();
            using (MidiPipe pc = new MidiPipe(abortedReady.FromPc, null)) {
                abortedReady.Pc = pc; abortedReady.AfterHelloAck = pc.Abort;
                Rejected(delegate { pc.Connect(); }, "abort wins over a simultaneous ready acknowledgement");
            }
            Check(abortedReady.Closes == 0, "Aborted handshake sent CLOSE");
            PhoneMidi failedClose = new PhoneMidi(); int failedCloseCleanups = 0;
            MidiPipe failedClosePc = new MidiPipe(failedClose.FromPc, delegate { failedCloseCleanups++; });
            failedClose.Pc = failedClosePc; failedClosePc.Connect(); failedClose.FailClose = true;
            failedClosePc.Close(); failedClosePc.Close();
            Check(failedClose.Closes == 1 && failedCloseCleanups == 1, "Failed CLOSE did not clean up exactly once");
            Rejected(delegate { failedClosePc.ReadByte(); }, "failed CLOSE still aborts local readers");
            PhoneMidi concurrentClose = new PhoneMidi(); int concurrentCleanups = 0;
            MidiPipe concurrentPc = new MidiPipe(concurrentClose.FromPc, delegate { Interlocked.Increment(ref concurrentCleanups); });
            concurrentClose.Pc = concurrentPc; concurrentPc.Connect();
            using (ManualResetEvent closeEntered = new ManualResetEvent(false))
            using (ManualResetEvent releaseClose = new ManualResetEvent(false))
            using (ManualResetEvent closeFinished = new ManualResetEvent(false))
            using (ManualResetEvent waiterFinished = new ManualResetEvent(false)) {
                concurrentClose.OnClose = delegate { closeEntered.Set(); releaseClose.WaitOne(3000, false); };
                Wire.Run(delegate { try { concurrentPc.Close(); } finally { closeFinished.Set(); } });
                Check(closeEntered.WaitOne(1000, false), "Graceful CLOSE was never sent");
                bool readRejected = false;
                Wire.Run(delegate { try { concurrentPc.ReadByte(); } catch (IOException) { readRejected = true; } finally { waiterFinished.Set(); } });
                try {
                    Check(waiterFinished.WaitOne(1000, false) && readRejected, "Sending CLOSE held stream state or did not wake reader");
                    concurrentPc.Close();
                    Check(concurrentClose.Closes == 1, "Concurrent disposal sent a second CLOSE");
                } finally { releaseClose.Set(); }
                Check(closeFinished.WaitOne(1000, false), "Graceful CLOSE cleanup did not finish");
                Check(concurrentCleanups == 1, "Concurrent disposal repeated cleanup");
                Pass("blocked CLOSE does not hold stream state and concurrent disposal is idempotent");
            }
            Console.WriteLine("TOTAL " + passed + " passed");
        } catch (Exception ex) { Console.Error.WriteLine(ex); Environment.ExitCode = 1; }
    }
}
