package app.wireroute.addon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/* JADX INFO: loaded from: classes4.dex */
public final class ResilienceAdvisor {
    public static final String CURRENT_CORE_LIMITATION = "Сейчас используется обычный WireGuard. Рабочее ядро сохранено без замены, поэтому оно не маскирует свой UDP-почерк. Переподключение и keepalive могут повысить стабильность, но не являются обходом DPI.";
    private static final int MOBILE_KEEPALIVE_SECONDS = 25;
    private static final int SAFE_IPV6_MTU = 1280;
    public static final String STABILITY_PRESET_NOTICE = "Добавить PersistentKeepalive = 25 для пиров и переподключить VPN? Это поддерживает NAT-сессию после простоя, но не гарантирует скорость и не скрывает WireGuard от DPI. Исходный профиль нужно сохранить для отмены.";
    public static final String STRICT_ALLOWLIST_LIMITATION = "Если сеть пропускает только утверждённые IP-адреса или сервисы, приложение не может гарантированно достичь произвольного VPN-сервера. Ни один локальный переключатель не даёт гарантии обхода строгого белого списка.";

    private ResilienceAdvisor() {
    }

    public static Assessment analyze(String str) {
        if (str == null || str.trim().isEmpty()) {
            return new Assessment(false, false, null, 0, 0, false, Collections.singletonList("Профиль не загружен."));
        }
        Iterator<String> it = lines(str).iterator();
        String strSectionName = "";
        boolean z = false;
        int i = 0;
        boolean z2 = false;
        int i2 = 0;
        boolean zEquals = false;
        boolean zEquals2 = false;
        Integer positiveInt = null;
        boolean zEquals3 = false;
        while (it.hasNext()) {
            String strTrim = stripComment(it.next()).trim();
            if (!strTrim.isEmpty()) {
                if (isSection(strTrim)) {
                    if (zEquals3 && z) {
                        i++;
                    }
                    strSectionName = sectionName(strTrim);
                    zEquals3 = "peer".equals(strSectionName);
                    if (!zEquals3) {
                        z = false;
                    } else {
                        i2++;
                        z = false;
                    }
                } else {
                    int iIndexOf = strTrim.indexOf(61);
                    if (iIndexOf > 0) {
                        String strNormalizeKey = normalizeKey(strTrim.substring(0, iIndexOf));
                        String strTrim2 = strTrim.substring(iIndexOf + 1).trim();
                        if ("interface".equals(strSectionName) && "mtu".equals(strNormalizeKey)) {
                            positiveInt = parsePositiveInt(strTrim2);
                        }
                        if ("peer".equals(strSectionName) && "persistentkeepalive".equals(strNormalizeKey)) {
                            Integer positiveInt2 = parsePositiveInt(strTrim2);
                            z = positiveInt2 != null && positiveInt2.intValue() > 0;
                        }
                        if (isAmneziaKey(strNormalizeKey)) {
                            z2 = true;
                        }
                        if ("peer".equals(strSectionName) && "allowedips".equals(strNormalizeKey)) {
                            for (String str2 : strTrim2.split(",")) {
                                String strTrim3 = str2.trim();
                                zEquals |= "0.0.0.0/0".equals(strTrim3);
                                zEquals2 |= "::/0".equals(strTrim3);
                            }
                        }
                    }
                }
            }
        }
        if (zEquals3 && z) {
            i++;
        }
        boolean z3 = zEquals2;
        ArrayList arrayList = new ArrayList();
        if (z2) {
            arrayList.add("В профиле есть параметры AmneziaWG, но текущее ядро WireRoute — обычный WireGuard и не должно запускать такой профиль как AmneziaWG.");
        } else {
            arrayList.add("Профиль использует обычный WireGuard. Его UDP-сигнатура не маскируется.");
        }
        if (positiveInt == null) {
            arrayList.add("MTU не задан явно, поэтому исходный Android-backend WireRoute уже использует безопасное значение 1280. Добавление такой же строки ничего не изменит.");
        } else if (positiveInt.intValue() > 1280) {
            arrayList.add("MTU выше 1280. Если видео зависает только через VPN, контрольная проба с MTU 1280 поможет проверить потерю крупных пакетов.");
        } else {
            arrayList.add("MTU уже не выше 1280; простое уменьшение MTU вряд ли является следующим полезным шагом.");
        }
        if (i2 > 0 && i < i2) {
            arrayList.add("Не у всех пиров включён PersistentKeepalive. Значение 25 секунд иногда помогает мобильному NAT после простоя, но не лечит потерю пакетов во время активного просмотра видео.");
        }
        if (!zEquals || !z3) {
            arrayList.add("Профиль не выглядит полным маршрутом одновременно для IPv4 и IPv6; часть трафика может идти не через VPN согласно самому профилю.");
        }
        return new Assessment(true, z2, positiveInt, i2, i, zEquals && z3, arrayList);
    }

    public static Candidate buildMobileStabilityCandidate(String str) {
        if (str == null || str.trim().isEmpty()) {
            throw new IllegalArgumentException("empty-config");
        }
        if (analyze(str).hasAmneziaParameters) {
            throw new IllegalArgumentException("amneziawg-needs-compatible-engine");
        }
        List<String> listLines = lines(str);
        ArrayList arrayList = new ArrayList();
        ArrayList arrayList2 = new ArrayList();
        String strSectionName = "";
        boolean z = false;
        boolean zEquals = false;
        for (String str2 : listLines) {
            String strTrim = stripComment(str2).trim();
            if (isSection(strTrim)) {
                appendSectionDefaults(arrayList, arrayList2, strSectionName, z);
                strSectionName = sectionName(strTrim);
                zEquals |= "interface".equals(strSectionName);
                z = false;
            } else {
                int iIndexOf = strTrim.indexOf(61);
                if (iIndexOf > 0) {
                    String strNormalizeKey = normalizeKey(strTrim.substring(0, iIndexOf));
                    if ("peer".equals(strSectionName) && "persistentkeepalive".equals(strNormalizeKey)) {
                        z = true;
                    }
                }
            }
            arrayList.add(str2);
        }
        appendSectionDefaults(arrayList, arrayList2, strSectionName, z);
        if (!zEquals) {
            throw new IllegalArgumentException("missing-interface");
        }
        String str3 = str.contains("\r\n") ? "\r\n" : "\n";
        String strJoin = join(arrayList, str3);
        if (str.endsWith("\n") || str.endsWith("\r")) {
            strJoin = strJoin + str3;
        }
        return new Candidate(strJoin, arrayList2);
    }

    public static List<Technology> technologies() {
        ArrayList arrayList = new ArrayList();
        arrayList.add(new Technology("Переподключение, MTU, keepalive", "Доступно сейчас", "Повышает стабильность в отдельных сетях, но не скрывает WireGuard от DPI."));
        arrayList.add(new Technology("AmneziaWG 2/3", "Нужны новое ядро и совместимый сервер", "Меняет узнаваемые пакеты WireGuard; параметры профиля должны поддерживаться клиентом и сервером."));
        arrayList.add(new Technology("Xray: VLESS + REALITY / XHTTP / ECH", "Нужны Xray и отдельный серверный профиль", "Маскировка TLS и скрытие части метаданных требуют согласованной конфигурации; это не функция обычного WireGuard."));
        arrayList.add(new Technology("Hysteria2 / TUIC", "Нужны QUIC-движок и сервер", "Может хорошо переносить потери, но зависит от доступности UDP/QUIC."));
        arrayList.add(new Technology("MASQUE CONNECT-IP", "Нужны совместимые MASQUE-клиент и сервер", "Стандартизованный IP-туннель поверх HTTP; текущий backend его не реализует."));
        return Collections.unmodifiableList(arrayList);
    }

    private static void appendSectionDefaults(List<String> list, List<String> list2, String str, boolean z) {
        if ("peer".equals(str) && !z) {
            list.add("PersistentKeepalive = 25");
            list2.add("Добавить PersistentKeepalive 25 для пира");
        }
    }

    private static List<String> lines(String str) {
        String[] strArrSplit = str.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        ArrayList arrayList = new ArrayList();
        int length = (strArrSplit.length <= 0 || !strArrSplit[strArrSplit.length + (-1)].isEmpty()) ? strArrSplit.length : strArrSplit.length - 1;
        for (int i = 0; i < length; i++) {
            arrayList.add(strArrSplit[i]);
        }
        return arrayList;
    }

    private static String stripComment(String str) {
        int iIndexOf = str.indexOf(35);
        int iIndexOf2 = str.indexOf(59);
        if (iIndexOf < 0) {
            iIndexOf = -1;
        }
        if (iIndexOf2 < 0 || (iIndexOf >= 0 && iIndexOf2 >= iIndexOf)) {
            iIndexOf2 = iIndexOf;
        }
        return iIndexOf2 < 0 ? str : str.substring(0, iIndexOf2);
    }

    private static boolean isSection(String str) {
        return str.length() >= 3 && str.charAt(0) == '[' && str.charAt(str.length() - 1) == ']';
    }

    private static String sectionName(String str) {
        return str.substring(1, str.length() - 1).trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeKey(String str) {
        return str.trim().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    private static boolean isAmneziaKey(String str) {
        if ("jc".equals(str) || "jmin".equals(str) || "jmax".equals(str) || "headerprotectionkey".equals(str) || "contentpaddingaddition".equals(str) || "rekeyaftertime".equals(str) || "rekeytimeout".equals(str) || "rejectaftertime".equals(str) || "keepalivetimeout".equals(str) || "maxhandshakeattempts".equals(str)) {
            return true;
        }
        return str.length() == 2 && (str.charAt(0) == 's' || str.charAt(0) == 'h' || str.charAt(0) == 'i') && str.charAt(1) >= '1' && str.charAt(1) <= '5';
    }

    private static Integer parsePositiveInt(String str) {
        try {
            int i = Integer.parseInt(str.trim());
            if (i >= 0) {
                return Integer.valueOf(i);
            }
            return null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String join(List<String> list, String str) {
        StringBuilder sb = new StringBuilder();
        for (String str2 : list) {
            if (sb.length() > 0) {
                sb.append(str);
            }
            sb.append(str2);
        }
        return sb.toString();
    }

    public static final class Assessment {
        public final boolean fullTunnelV4AndV6;
        public final boolean hasAmneziaParameters;
        public final boolean hasProfile;
        public final Integer mtu;
        public final List<String> notes;
        public final int peerCount;
        public final int peersWithKeepalive;

        Assessment(boolean z, boolean z2, Integer num, int i, int i2, boolean z3, List<String> list) {
            this.hasProfile = z;
            this.hasAmneziaParameters = z2;
            this.mtu = num;
            this.peerCount = i;
            this.peersWithKeepalive = i2;
            this.fullTunnelV4AndV6 = z3;
            this.notes = Collections.unmodifiableList(new ArrayList(list));
        }
    }

    public static final class Candidate {
        public final List<String> changes;
        public final String config;

        Candidate(String str, List<String> list) {
            this.config = str;
            this.changes = Collections.unmodifiableList(new ArrayList(list));
        }

        public boolean changed() {
            return !this.changes.isEmpty();
        }
    }

    public static final class Technology {
        public final String explanation;
        public final String name;
        public final String status;

        Technology(String str, String str2, String str3) {
            this.name = str;
            this.status = str2;
            this.explanation = str3;
        }
    }
}

