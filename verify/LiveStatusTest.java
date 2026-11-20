import com.smcscanner.engine.LiveStatus;
public class LiveStatusTest {
    static int fail = 0, n = 0;
    static void ck(boolean ok, String m) { n++; if (!ok) { fail++; System.out.println("FAIL " + m); } }
    public static void main(String[] a) {
        // buy: entry 100, stop 98, target 106
        String[] ph = {"stop","target","watch","inTrade","far"};
        ck(LiveStatus.describe(true, true, false, 100, 98, 106, 97.5).phase.equals("stop"), "buy stop");
        ck(LiveStatus.describe(true, true, false, 100, 98, 106, 106.5).phase.equals("target"), "buy target");
        ck(LiveStatus.describe(true, false, false, 100, 98, 106, 103).phase.equals("watch"), "watch");
        ck(LiveStatus.describe(true, true, false, 100, 98, 106, 99.5).phase.equals("inTrade"), "buy at entry");
        ck(LiveStatus.describe(true, true, false, 100, 98, 106, 104).text.length() > 0, "far text");
        ck(LiveStatus.describe(true, true, false, 100, 98, 106, 104).tone == 0, "far neutral");
        // sell mirrored: entry 100, stop 102, target 94
        ck(LiveStatus.describe(false, true, false, 100, 102, 94, 102.5).phase.equals("stop"), "sell stop");
        ck(LiveStatus.describe(false, true, false, 100, 102, 94, 93.5).phase.equals("target"), "sell target");
        ck(LiveStatus.describe(false, true, false, 100, 102, 94, 100.5).phase.equals("inTrade"), "sell at entry");
        ck(LiveStatus.describe(false, true, true, 100, 102, 94, 98).tone == 1, "sell in profit good");
        ck(LiveStatus.describe(true, true, false, 100, 98, 106, 0).phase.equals("watch"), "no price");
        // text changes as price moves
        ck(!LiveStatus.describe(true, true, false, 100, 98, 106, 104).text.equals(LiveStatus.describe(true, true, false, 100, 98, 106, 102).text), "text follows price");
        System.out.println("LiveStatus checks " + n + " failures " + fail);
        System.exit(fail == 0 ? 0 : 1);
    }
}
