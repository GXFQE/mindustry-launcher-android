package io.mdt.plugins.usagestats.core;

import java.util.Arrays;
import java.util.Collections;

/** PC 侧判据自检：`bash plugins/usagestats/build.sh check`。 */
public final class Check {

    public static void main(String[] args) {
        int fail = 0;
        fail += ok(Playtime.value("total=123\nslot=x\n", "total") == 123, "value: total=123");
        fail += ok(Playtime.value("total=abc\n", "total") == -1, "bad number -> -1");
        fail += ok(Playtime.value("slot=x\n", "total") == -1, "missing key -> -1");
        fail += ok(Playtime.value(null, "total") == -1, "null text -> -1");
        fail += ok(Playtime.sumTotals(Arrays.asList("total=100\n", "total=50\n", "total=bad\n")) == 150,
                "sum: 100 + 50 + bad");
        fail += ok(Playtime.sumTotals(Collections.<String>emptyList()) == 0, "empty list -> 0");
        int[] a = Playtime.hm(3_600_000L + 12 * 60_000L);
        fail += ok(a[0] == 1 && a[1] == 12, "hm: 1h12");
        int[] b = Playtime.hm(-5);
        fail += ok(b[0] == 0 && b[1] == 0, "hm: negative -> 0/0");
        System.out.println(fail == 0 ? "== USAGESTATS CHECK OK ==" : "!! " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    private static int ok(boolean cond, String what) {
        System.out.println((cond ? "  ok   " : "  FAIL ") + what);
        return cond ? 0 : 1;
    }

    private Check() {}
}
