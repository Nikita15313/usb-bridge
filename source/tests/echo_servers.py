import socket, threading, hashlib
def serve(c):
    with c:
        head=c.recv(1)
        if head==b'H':
            data=bytearray()
            while chunk:=c.recv(16384): data.extend(chunk)
            c.sendall(hashlib.sha256(data).digest())
        else:
            if head:c.sendall(head)
            while chunk:=c.recv(16384): c.sendall(chunk)
def tcp():
    s=socket.socket();s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);s.bind(('127.0.0.1',38080));s.listen()
    while True:
        c,_=s.accept();threading.Thread(target=serve,args=(c,),daemon=True).start()
def udp():
    s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);s.bind(('127.0.0.1',38081))
    while True:
        b,a=s.recvfrom(65507);s.sendto(b,a)
threading.Thread(target=tcp,daemon=True).start()
udp()
