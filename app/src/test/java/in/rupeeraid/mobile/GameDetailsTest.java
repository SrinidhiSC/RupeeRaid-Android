package in.rupeeraid.mobile;

import java.io.IOException;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class GameDetailsTest {
    @Test public void itadRelationshipsKeepCatalogArtworkAndDistinguishEmptyFromMissing() throws Exception {
        JSONObject assets=new JSONObject().put("banner145","https://example.com/package.jpg");
        JSONObject item=new JSONObject().put("id","018d937f-3e52-70a0-ae29-1e841def686c")
                .put("title","The Walking Dead - Ultimate Steam Bundle").put("assets",assets);
        JSONObject games=new JSONObject().put("packages",new JSONArray().put(item)).put("dlcs",JSONObject.NULL);
        JSONObject row=PublicGameDetails.relatedGames(games).getJSONObject(0);
        assertEquals("package",row.getString("type"));
        assertEquals(assets,row.getJSONObject("assets"));
        games.put("packages",new JSONArray());
        assertEquals(0,PublicGameDetails.relatedGames(games).length());
        games.remove("packages");
        assertThrows(IOException.class,()->PublicGameDetails.relatedGames(games));
    }
    @Test public void packagesUseMembershipEvenWhenTheNameDoesNotMatch() throws Exception {
        String html="<h2 id=\"bundle_purchase_label_77953\">Buy Klei 20th Anniversary Anthology <span>BUNDLE</span></h2>"
                +"<h2 id=\"game_area_purchase_section_add_to_cart_title_802975\">Buy A Plague Tale Bundle</h2>"
                +"<a id=\"package-item-802975-752590\"></a>"
                +"<h2 id=\"game_area_purchase_section_add_to_cart_title_340715\">Buy A Plague Tale: Innocence</h2>";
        JSONArray rows=PublicGameDetails.parsePackages(752590,html+html);
        assertEquals(2,rows.length());
        assertEquals("bundle/77953",rows.getJSONObject(0).getString("steamPath"));
        assertEquals("sub/802975",rows.getJSONObject(1).getString("steamPath"));
    }

    @Test public void extractsEditorialOnlyForTheRequestedGame() throws Exception {
        String page="var page = [\"Game\",{\"game\":{\"id\":\"abc\"},\"detail\":{\"summary\":{\"text\":\"Story\"},\"hltb\":{\"main\":14014}}},true];";
        JSONObject result=PublicGameDetails.parseEditorial("abc",page);
        assertEquals("Story",result.getJSONObject("summary").getString("text"));
        assertEquals("4 h",GamePage.duration(result.getJSONObject("hltb").getLong("main")));
        assertEquals("30 min",GamePage.duration(1800));
        assertEquals("",GamePage.duration(0));
        assertThrows(IOException.class,()->PublicGameDetails.parseEditorial("other",page));
    }

    @Test public void preferredDrmChangesTheBestPriceAndKeepsWishlistMembership() throws Exception {
        JSONObject epic=new JSONObject().put("shop","Epic Game Store").put("paise",1000);
        JSONObject steam=new JSONObject().put("shop","GameBillet").put("paise",2000).put("drm",new JSONArray().put("Steam"));
        JSONObject game=new JSONObject().put("id","sample").put("title","Sample").put("offers",new JSONArray().put(epic).put(steam));
        JSONArray titles=new JSONArray().put(game);
        JSONObject data=new JSONObject().put("deals",titles).put("wishlist",titles);
        assertSame(steam,Market.best(game,Market.ALL,Set.of("Steam")));
        assertSame(epic,Market.best(game,Market.ALL));
        assertTrue(Market.rows(data,"deals",Market.ALL,"","Default",false,Set.of("GOG")).isEmpty());
        assertEquals(1,Market.rows(data,"wishlist",Market.ALL,"","Default",false,Set.of("GOG")).size());
    }
    @Test public void activationMatchesAcrossRetailersAndKeepsAnyInclusive() throws Exception {
        JSONObject offer=new JSONObject().put("shop","Humble Store").put("drm",new JSONArray()
                .put(new JSONObject().put("name","Steam")).put(new JSONObject().put("name","Drm Free")));
        assertTrue(DrmFilter.matches(offer,Set.of("Steam")));
        assertTrue(DrmFilter.matches(offer,Set.of("Epic","DRM Free")));
        assertFalse(DrmFilter.matches(offer,Set.of("Microsoft Store")));
        assertTrue(DrmFilter.matches(new JSONObject().put("shop","Epic Game Store"),Set.of("Epic")));
        assertTrue(DrmFilter.matches(new JSONObject(),Set.of()));
        assertFalse(DrmFilter.matches(new JSONObject(),Set.of("GOG")));
    }
}
