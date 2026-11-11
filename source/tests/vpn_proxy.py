"""Local test fixture. Bind explicitly to the PC address passed by the harness.
Never included in the end-user kit. SOCKS TCP and HEV UDP-over-TCP for an
independent Android VPN APK; both data paths must traverse USB Bridge.
"""
import argparse, socket, socketserver, struct, threading, select

def exact(sock,n):
    data=b''
    while len(data)<n:
        b=sock.recv(n-len(data))
        if not b: raise EOFError()
        data+=b
    return data

def address(sock,kind):
    if kind==1:return socket.inet_ntoa(exact(sock,4))
    if kind==3:return exact(sock,exact(sock,1)[0]).decode('ascii')
    raise ValueError('IPv4 test only')

class Proxy(socketserver.BaseRequestHandler):
    def handle(self):
        sockets=[];lock=threading.Lock()
        try:
            head=exact(self.request,2);exact(self.request,head[1]);self.request.sendall(b'\x05\x00')
            head=exact(self.request,4);host=address(self.request,head[3]);port=struct.unpack('!H',exact(self.request,2))[0]
            if head[1]==1:
                upstream=socket.create_connection((host,port),15);sockets.append(upstream);self.request.sendall(bytes([5,0,0,1])+bytes(6));upstream.settimeout(None)
                print('TCP',host,port,flush=True)
                while True:
                    ready,_,_=select.select([upstream,self.request],[],[],60)
                    if not ready:break
                    for origin in ready:
                        data=origin.recv(4096)
                        if not data:return
                        (self.request if origin is upstream else upstream).sendall(data)
            elif head[1]==5:
                self.request.sendall(bytes([5,0,0,1])+bytes(6));flows={}
                def reply(sock,host,port):
                    try:
                        while True:
                            data=sock.recv(65507)
                            packet=struct.pack('!HBB',len(data),10,1)+socket.inet_aton(host)+struct.pack('!H',port)+data
                            with lock:self.request.sendall(packet)
                    except (OSError,EOFError):pass
                while True:
                    h=exact(self.request,4);host=address(self.request,h[3]);port=struct.unpack('!H',exact(self.request,2))[0];data=exact(self.request,int.from_bytes(h[:2],'big'));key=(host,port)
                    if key not in flows:
                        if len(flows)>=64:raise ValueError('Flow limit')
                        sock=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);sock.settimeout(30);sock.connect(key);flows[key]=sock;sockets.append(sock)
                        threading.Thread(target=reply,args=(sock,host,port),daemon=True).start();print('UDP',host,port,flush=True)
                    flows[key].send(data)
        except (OSError,EOFError,ValueError):pass
        finally:
            for sock in sockets:sock.close()

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--bind',required=True);args=parser.parse_args()
    socketserver.ThreadingTCPServer.allow_reuse_address=True
    server=socketserver.ThreadingTCPServer((args.bind,39500),Proxy);server.daemon_threads=True
    print('Test proxy ready',args.bind,flush=True);server.serve_forever()
