// SPDX-License-Identifier: Apache-2.0
// WireRoute addition: packet-preserving userspace link for one Android VPN TUN.
package main

import (
    "os"
    "sync"
    "golang.org/x/sys/unix"
    "golang.zx2c4.com/wireguard/tun"
)

// Android's visible TUN stays owned by the Java dispatcher. A Unix datagram
// socket carries only IP packets between that dispatcher and WireGuard. The
// cryptographic implementation and peer protocol are the upstream versions.
type packetTun struct {
    file *os.File
    events chan tun.Event
    once sync.Once
}
func openAndroidTun(fd int) (tun.Device, string, error) {
    kind, err := unix.GetsockoptInt(fd, unix.SOL_SOCKET, unix.SO_TYPE)
    if err != nil || kind != unix.SOCK_DGRAM {
        return tun.CreateUnmonitoredTUNFromFD(fd)
    }
    if err = unix.SetNonblock(fd, true); err != nil { return nil, "", err }
    t := &packetTun{file:os.NewFile(uintptr(fd), "wireroute-packet-link"), events:make(chan tun.Event, 1)}
    return t, "wireroute-packet-link", nil
}
func (t *packetTun) File() *os.File { return t.file }
func (t *packetTun) Name() (string,error) { return "wireroute-packet-link",nil }
// The real Android TUN applies the profile MTU. This internal link must not
// truncate a packet that Android has already accepted.
func (t *packetTun) MTU() (int,error) { return 65535,nil }
func (t *packetTun) BatchSize() int { return 1 }
func (t *packetTun) Events() <-chan tun.Event { return t.events }
func (t *packetTun) Read(bufs [][]byte,sizes []int,offset int) (int,error) {
    n,err:=t.file.Read(bufs[0][offset:]); if err!=nil{return 0,err}; sizes[0]=n;return 1,nil
}
func (t *packetTun) Write(bufs [][]byte,offset int) (int,error) {
    for i,b:=range bufs {if _,err:=t.file.Write(b[offset:]);err!=nil{return i,err}}
    return len(bufs),nil
}
func (t *packetTun) Close() error {
    var err error
    t.once.Do(func(){err=t.file.Close();close(t.events)})
    return err
}
