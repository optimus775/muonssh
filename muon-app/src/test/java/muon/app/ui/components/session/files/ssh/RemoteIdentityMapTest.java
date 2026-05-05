package muon.app.ui.components.session.files.ssh;

import org.junit.Test;

import static org.junit.Assert.*;

public class RemoteIdentityMapTest {
    @Test
    public void parsesPasswdAndKeepsFirstDuplicateId() {
        RemoteIdentityMap map = RemoteIdentityMap.parsePasswd(
                "root:x:0:0:root:/root:/bin/bash\n"
                + "alice:x:1000:1000:Alice:/home/alice:/bin/bash\n"
                + "duplicate:x:1000:1000:Duplicate:/home/duplicate:/bin/bash\n"
                + "bad:x:not-a-number:1001:Bad:/tmp:/bin/false\n");

        assertEquals(Integer.valueOf(0), map.getId("root"));
        assertEquals(Integer.valueOf(1000), map.getId("alice"));
        assertEquals("alice", map.getName(1000));
        assertNull(map.getId("bad"));
        assertNull(map.getName(9999));
    }

    @Test
    public void parsesGroupsAndSkipsMalformedRows() {
        RemoteIdentityMap map = RemoteIdentityMap.parseGroup(
                "root:x:0:\n"
                + "staff:x:50:alice,bob\n"
                + "broken:x:not-a-number:\n");

        assertEquals(Integer.valueOf(0), map.getId("root"));
        assertEquals(Integer.valueOf(50), map.getId("staff"));
        assertEquals("staff", map.getName(50));
        assertNull(map.getId("broken"));
    }

    @Test
    public void validatesKnownNamesKnownIdsAndUnknownNumericIds() {
        RemoteIdentityMap map = RemoteIdentityMap.parsePasswd("root:x:0:0:root:/root:/bin/bash\n");

        RemoteIdentityMap.Resolution knownName = RemoteIdentityMap.resolve("root", "", map);
        assertTrue(knownName.isValid());
        assertEquals(Integer.valueOf(0), knownName.getId());

        RemoteIdentityMap.Resolution knownId = RemoteIdentityMap.resolve("", "0", map);
        assertTrue(knownId.isValid());
        assertEquals(Integer.valueOf(0), knownId.getId());

        RemoteIdentityMap.Resolution unknownNumeric = RemoteIdentityMap.resolve("", "12345", map);
        assertTrue(unknownNumeric.isValid());
        assertEquals(Integer.valueOf(12345), unknownNumeric.getId());

        RemoteIdentityMap.Resolution unknownNameWithNumeric = RemoteIdentityMap.resolve("service-user", "12345", map);
        assertTrue(unknownNameWithNumeric.isValid());
        assertEquals(Integer.valueOf(12345), unknownNameWithNumeric.getId());

        assertFalse(RemoteIdentityMap.resolve("root", "12345", map).isValid());
        assertFalse(RemoteIdentityMap.resolve("service-user", "", map).isValid());
        assertFalse(RemoteIdentityMap.resolve("", "not-a-number", map).isValid());
    }
}
