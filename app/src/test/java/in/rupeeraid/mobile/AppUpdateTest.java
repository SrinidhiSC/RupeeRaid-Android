package in.rupeeraid.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class AppUpdateTest {
    @Test public void comparesReleaseTagsNumerically(){
        assertTrue(AppUpdate.compare("v2.10","2.9.9")>0);
        assertTrue(AppUpdate.compare("v2.2","2.2.1")<0);
        assertEquals(0,AppUpdate.compare("v2.2.0","2.2"));
        assertThrows(IllegalArgumentException.class,()->AppUpdate.compare("preview","2.2"));
    }
}
