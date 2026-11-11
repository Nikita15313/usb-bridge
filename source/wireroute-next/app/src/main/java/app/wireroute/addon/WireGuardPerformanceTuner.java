package app.wireroute.addon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/* JADX INFO: loaded from: classes4.dex */
public final class WireGuardPerformanceTuner {
    public static final int ANDROID_BACKEND_DEFAULT_MTU = 1280;
    public static final int NAT_KEEPALIVE_SECONDS = 25;

    private WireGuardPerformanceTuner() {
    }

    public static Assessment inspect(String str) {
        if (str == null || str.trim().isEmpty()) {
            return new Assessment(false, 0, 0, 0, null, ANDROID_BACKEND_DEFAULT_MTU);
        }
        Parsed parsed = parse(str);
        int i = 0;
        int i2 = 0;
        for (PeerBlock peerBlock : parsed.peers) {
            if (peerBlock.hasEndpoint) {
                i++;
                if (!peerBlock.hasNonZeroKeepalive) {
                    i2++;
                }
            }
        }
        return new Assessment(parsed.interfaceCount == 1 && !parsed.peers.isEmpty(), parsed.peers.size(), i, i2, parsed.explicitMtu, parsed.explicitMtu == null ? ANDROID_BACKEND_DEFAULT_MTU : parsed.explicitMtu.intValue());
    }

    public static TuneResult applyNatStability(String str) {
        if (str == null || str.trim().isEmpty()) {
            throw new IllegalArgumentException("empty-config");
        }
        Parsed parsed = parse(str);
        if (parsed.interfaceCount != 1 || parsed.peers.isEmpty()) {
            throw new IllegalArgumentException("unsupported-config-layout");
        }
        ArrayList<String> arrayList = new ArrayList<>();
        int i = 0;
        for (Block block : parsed.blocks) {
            if (!(block instanceof PeerBlock)) {
                arrayList.addAll(block.lines);
            } else {
                PeerBlock peerBlock = (PeerBlock) block;
                if (!peerBlock.hasEndpoint || peerBlock.hasNonZeroKeepalive) {
                    arrayList.addAll(peerBlock.lines);
                } else {
                    ArrayList<String> arrayList2 = new ArrayList<>(peerBlock.lines);
                    if (peerBlock.keepaliveLine >= 0) {
                        arrayList2.set(peerBlock.keepaliveLine, "PersistentKeepalive = 25");
                    } else {
                        arrayList2.add(peerBlock.endpointLine >= 0 ? peerBlock.endpointLine + 1 : arrayList2.size(), "PersistentKeepalive = 25");
                    }
                    arrayList.addAll(arrayList2);
                    i++;
                }
            }
        }
        String strM11m = String.join("\n", arrayList);
        if (parsed.endedWithNewline && !strM11m.endsWith("\n")) {
            strM11m = strM11m + "\n";
        }
        return new TuneResult(strM11m, i, inspect(strM11m));
    }

    private static Parsed parse(String str) {
        boolean z = str.endsWith("\n") || str.endsWith("\r");
        String[] strArrSplit = str.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        ArrayList<Block> arrayList = new ArrayList();
        Block block = new Block();
        arrayList.add(block);
        Integer numPositiveInteger = null;
        int i = 0;
        for (int i2 = 0; i2 < strArrSplit.length; i2++) {
            if (i2 != strArrSplit.length - 1 || !strArrSplit[i2].isEmpty() || !z) {
                String str2 = strArrSplit[i2];
                String strTrim = str2.trim();
                if ("[Interface]".equalsIgnoreCase(strTrim)) {
                    block = new InterfaceBlock();
                    arrayList.add(block);
                    i++;
                } else if ("[Peer]".equalsIgnoreCase(strTrim)) {
                    block = new PeerBlock();
                    arrayList.add(block);
                }
                block.add(str2);
                KeyValue keyValue = keyValue(strTrim);
                if ((block instanceof InterfaceBlock) && keyValue != null && "mtu".equals(keyValue.key)) {
                    numPositiveInteger = positiveInteger(keyValue.value);
                } else if ((block instanceof PeerBlock) && keyValue != null) {
                    PeerBlock peerBlock = (PeerBlock) block;
                    int size = peerBlock.lines.size() - 1;
                    if ("endpoint".equals(keyValue.key)) {
                        peerBlock.hasEndpoint = !keyValue.value.isEmpty();
                        peerBlock.endpointLine = size;
                    } else if ("persistentkeepalive".equals(keyValue.key)) {
                        peerBlock.keepaliveLine = size;
                        Integer numPositiveOrZeroInteger = positiveOrZeroInteger(keyValue.value);
                        peerBlock.hasNonZeroKeepalive = numPositiveOrZeroInteger != null && numPositiveOrZeroInteger.intValue() > 0;
                    }
                }
            }
        }
        ArrayList arrayList2 = new ArrayList();
        for (Block block2 : arrayList) {
            if (block2 instanceof PeerBlock) {
                arrayList2.add((PeerBlock) block2);
            }
        }
        return new Parsed(arrayList, arrayList2, i, numPositiveInteger, z);
    }

    private static KeyValue keyValue(String str) {
        int iIndexOf;
        if (str.isEmpty() || str.startsWith("#") || str.startsWith(";") || (iIndexOf = str.indexOf(61)) <= 0) {
            return null;
        }
        return new KeyValue(str.substring(0, iIndexOf).trim().toLowerCase(Locale.ROOT), str.substring(iIndexOf + 1).trim());
    }

    private static Integer positiveInteger(String str) {
        Integer numPositiveOrZeroInteger = positiveOrZeroInteger(str);
        if (numPositiveOrZeroInteger == null || numPositiveOrZeroInteger.intValue() <= 0) {
            return null;
        }
        return numPositiveOrZeroInteger;
    }

    private static Integer positiveOrZeroInteger(String str) {
        if (str != null) {
            try {
                if (!str.isEmpty()) {
                    return Integer.valueOf(str);
                }
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public static final class Assessment {
        public final int effectiveMtu;
        public final int endpointPeerCount;
        public final Integer explicitMtu;
        public final int peerCount;
        public final int peersWithoutKeepalive;
        public final boolean supported;

        Assessment(boolean z, int i, int i2, int i3, Integer num, int i4) {
            this.supported = z;
            this.peerCount = i;
            this.endpointPeerCount = i2;
            this.peersWithoutKeepalive = i3;
            this.explicitMtu = num;
            this.effectiveMtu = i4;
        }

        public boolean needsNatKeepalive() {
            return this.supported && this.peersWithoutKeepalive > 0;
        }
    }

    public static final class TuneResult {
        public final Assessment assessment;
        public final int changedPeers;
        public final String config;

        TuneResult(String str, int i, Assessment assessment) {
            this.config = str;
            this.changedPeers = i;
            this.assessment = assessment;
        }
    }

    private static class Block {
        final List<String> lines;

        private Block() {
            this.lines = new ArrayList();
        }

        void add(String str) {
            this.lines.add(str);
        }
    }

    private static final class InterfaceBlock extends Block {
        private InterfaceBlock() {
            super();
        }
    }

    private static final class PeerBlock extends Block {
        int endpointLine;
        boolean hasEndpoint;
        boolean hasNonZeroKeepalive;
        int keepaliveLine;

        private PeerBlock() {
            super();
            this.endpointLine = -1;
            this.keepaliveLine = -1;
        }
    }

    private static final class KeyValue {
        final String key;
        final String value;

        KeyValue(String str, String str2) {
            this.key = str;
            this.value = str2;
        }
    }

    private static final class Parsed {
        final List<Block> blocks;
        final boolean endedWithNewline;
        final Integer explicitMtu;
        final int interfaceCount;
        final List<PeerBlock> peers;

        Parsed(List<Block> list, List<PeerBlock> list2, int i, Integer num, boolean z) {
            this.blocks = Collections.unmodifiableList(new ArrayList(list));
            this.peers = Collections.unmodifiableList(new ArrayList(list2));
            this.interfaceCount = i;
            this.explicitMtu = num;
            this.endedWithNewline = z;
        }
    }
}
