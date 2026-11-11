using System;
using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using UsbBridge;

class AdapterTest {
    static int Main() {
        foreach (NetworkInterface ni in NetworkInterface.GetAllNetworkInterfaces()) {
            if (ni.OperationalStatus != OperationalStatus.Up || ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
            IPInterfaceProperties props = ni.GetIPProperties();
            foreach (UnicastIPAddressInformation address in props.UnicastAddresses) {
                if (address.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                IPAddress dns = IPAddress.Parse("1.1.1.1");
                foreach (IPAddress candidate in props.DnsAddresses)
                    if (candidate.AddressFamily == AddressFamily.InterNetwork) { dns = candidate; break; }
                try {
                    Relay relay = new Relay(address.Address, props.GetIPv4Properties().Index, dns);
                    using (Socket socket = relay.Connect("www.gstatic.com", 80))
                    using (NetworkStream stream = new NetworkStream(socket, false)) {
                        byte[] request = Encoding.ASCII.GetBytes("GET /generate_204 HTTP/1.1\r\nHost: www.gstatic.com\r\nConnection: close\r\n\r\n");
                        stream.Write(request, 0, request.Length);
                        string response = new StreamReader(stream).ReadLine();
                        if (response == null || response.IndexOf(" 204 ") < 0) throw new Exception(response);
                        if (!((IPEndPoint)socket.LocalEndPoint).Address.Equals(address.Address)) throw new Exception("Wrong local address");
                        Console.WriteLine("PASS PC internet uses explicitly selected source address and IP_UNICAST_IF");
                        return 0;
                    }
                } catch (Exception ex) { Console.WriteLine("Adapter attempt: " + ex.Message); }
            }
        }
        Console.Error.WriteLine("FAIL no explicitly bound adapter reached internet");
        return 1;
    }
}
