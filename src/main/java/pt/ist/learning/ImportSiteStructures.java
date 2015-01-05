package pt.ist.learning;

import java.io.FileReader;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.zip.ZipInputStream;

import org.fenixedu.bennu.core.domain.Bennu;
import org.fenixedu.bennu.core.groups.AnyoneGroup;
import org.fenixedu.bennu.core.security.Authenticate;
import org.fenixedu.bennu.io.domain.GroupBasedFile;
import org.fenixedu.bennu.scheduler.custom.CustomTask;
import org.fenixedu.cms.domain.CMSFolder;
import org.fenixedu.cms.domain.CMSTheme;
import org.fenixedu.cms.domain.CMSThemeLoader;
import org.fenixedu.cms.domain.Category;
import org.fenixedu.cms.domain.Menu;
import org.fenixedu.cms.domain.MenuItem;
import org.fenixedu.cms.domain.Page;
import org.fenixedu.cms.domain.PersistentSiteViewersGroup;
import org.fenixedu.cms.domain.Post;
import org.fenixedu.cms.domain.PostFile;
import org.fenixedu.cms.domain.Site;
import org.fenixedu.cms.domain.component.StaticPost;
import org.fenixedu.commons.StringNormalizer;
import org.fenixedu.learning.domain.degree.DegreeSite;
import org.fenixedu.learning.domain.degree.DegreeSiteListener;
import org.fenixedu.learning.domain.executionCourse.ExecutionCourseListener;
import org.fenixedu.learning.domain.executionCourse.ExecutionCourseSite;
import org.joda.time.DateTime;

import pt.ist.fenix.domain.homepage.HomepageListener;
import pt.ist.fenix.domain.homepage.HomepageSite;
import pt.ist.fenixframework.Atomic.TxMode;
import pt.ist.fenixframework.FenixFramework;

import com.google.common.collect.Iterables;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class ImportSiteStructures extends CustomTask {

    @Override
    public void runTask() throws Exception {
        installThemes();
        Iterable<List<JsonElement>> chunks =
                Iterables.partition(new JsonParser().parse(new FileReader("/Users/jpc/Downloads/sites.json")).getAsJsonArray(),
                        100);
        int count = 0;
        int total = Iterables.size(chunks);
        taskLog("Processing %s chunks\n", total);
        for (List<JsonElement> chunk : chunks) {
            FenixFramework.atomic(() -> {
                for (JsonElement el : chunk) {
                    JsonObject json = el.getAsJsonObject();
                    if (!"Homepage".equals(json.get("type").getAsString())) {
                        continue;
                    }
                    Site site = FenixFramework.getDomainObject(json.get("site").getAsString());
                    if (!site.getMenusSet().isEmpty()) {
                        continue;
                    }

                    generateSlugs(site, json.has("path") ? json.get("path").getAsString() : null);
                    Menu menu = new Menu(site);
                    menu.setName(ExecutionCourseListener.MENU_TITLE);

                    createDefaultContents(site, menu);

                    process(site, json, menu, null);
                }
            });
            count++;
            taskLog("Processed %s/%s\n", count, total);
        }
    }

    private void createDefaultContents(Site site, Menu menu) {
        if (site instanceof ExecutionCourseSite) {
            ExecutionCourseListener.createDefaultContents(site, menu, Authenticate.getUser());
        }
        if (site instanceof HomepageSite) {
            HomepageListener.createDefaultContents(site, menu, Authenticate.getUser());
        }
        if (site instanceof DegreeSite) {
            DegreeSiteListener.createDefaultContents(site, menu, Authenticate.getUser());
        }
    }

    private void installThemes() {
        installTheme("fenixedu-learning-theme");
        installTheme("fenixedu-homepages-theme");
    }

    private void installTheme(String themeName) {
        InputStream in = getClass().getClassLoader().getResourceAsStream("META-INF/resources/WEB-INF/" + themeName + ".zip");
        ZipInputStream zin = new ZipInputStream(in);
        CMSThemeLoader.createFromZipStream(zin);
    }

    private void generateSlugs(Site site, String path) {
        site.setBennu(Bennu.getInstance());

        if (site.getCreatedBy() == null) {
            site.setCreatedBy(Authenticate.getUser());
        }

        if (site.getFolder() == null) {
            site.setFolder(folder(path));
        }

        for (Category cat : site.getCategoriesSet()) {
            if (cat.getCreatedBy() == null) {
                cat.setCreatedBy(Authenticate.getUser());
            }
            if (cat.getSlug() == null && cat.getName() != null && !cat.getName().isEmpty()) {
                cat.setSlug(StringNormalizer.slugify(cat.getName().getContent()));
            }
        }

        if (site instanceof ExecutionCourseSite) {
            ((ExecutionCourseSite) site).getExecutionCourse().setSiteUrl(site.getFullUrl());
        }

        if (site instanceof DegreeSite) {
            ((DegreeSite) site).getDegree().setSiteUrl(site.getFullUrl());
        }

        if (site.getSlug() == null || site.getExternalId().equals(site.getSlug())) {
            site.setSlug(StringNormalizer.slugify(site.getBaseUrl()));
        }

        site.getPostSet()
                .stream()
                .filter(post -> (post.getExternalId().equals(post.getSlug()) || post.getSlug() == null) && post.getName() != null
                        && post.getName().getContent() != null).forEach(post -> {
                    post.setSlug(StringNormalizer.slugify(post.getName().getContent()));
                    if (post.getCreatedBy() == null) {
                        post.setCreatedBy(Authenticate.getUser());
                    }
                });

        try {
            Constructor<?> ctor = PersistentSiteViewersGroup.class.getDeclaredConstructor(Site.class);
            ctor.setAccessible(true);
            ctor.newInstance(site);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        if (site.getTheme() == null) {
            site.setTheme(themeForSite(site));
        }
    }

    private CMSTheme themeForSite(Site site) {
        if (site instanceof ExecutionCourseSite || site instanceof DegreeSite) {
            return CMSTheme.forType("fenixedu-learning-theme");
        }
        if (site instanceof HomepageSite) {
            return CMSTheme.forType("fenixedu-homepages-theme");
        }
        return null;
    }

    private CMSFolder folder(String string) {
        return Bennu.getInstance().getCmsFolderSet().stream()
                .filter(folder -> folder.getFunctionality().getFullPath().equals(string)).findAny().get();
    }

    @Override
    public TxMode getTxMode() {
        return TxMode.READ;
    }

    private void process(Site site, JsonObject json, Menu menu, MenuItem parent) {
        if (json.has("sections")) {
            for (JsonElement ell : json.get("sections").getAsJsonArray()) {
                JsonObject section = ell.getAsJsonObject();
                if (section.has("customPath")) {
                    // TODO Determine what to do here
                    continue;
                }
                MenuItem root =
                        processItem(site, menu, parent, section.get("items").getAsJsonArray().get(0).getAsJsonObject(), section
                                .get("visible").getAsBoolean());
                process(site, section, menu, root);
            }
        }
    }

    private MenuItem processItem(Site site, Menu menu, MenuItem parent, JsonObject item, boolean sectionVisible) {
        Post post = FenixFramework.getDomainObject(item.get("id").getAsString());
        site.addPost(post);
        if (post.getName() != null) {
            post.setSlug(StringNormalizer.slugify(post.getName().getContent()));
        }
        if (post.getCreatedBy() == null) {
            post.setCreatedBy(Authenticate.getUser());
        }
        if (post.getCreationDate() == null) {
            post.setCreationDate(DateTime.now());
        }
        MenuItem menuItem = new MenuItem(menu);
        if (parent == null) {
            menu.addToplevelItems(menuItem);
        } else {
            menuItem.setParent(parent);
        }
        menuItem.setName(post.getName());
        menuItem.setPosition(item.get("order").getAsInt() + 100);
        Page page = new Page(site);
        if (site.getInitialPage() == null) {
            site.setInitialPage(page);
        }
        menuItem.setPage(page);
        page.setName(post.getName());
        page.setCanViewGroup(AnyoneGroup.get());
        page.setPublished(sectionVisible && item.get("visible").getAsBoolean());
        page.addComponents(new StaticPost(post));
        page.setTemplate(site.getTheme().templateForType("view"));
        if (item.has("files")) {
            int idx = 0;
            for (JsonElement fileEl : item.get("files").getAsJsonArray()) {
                GroupBasedFile file = FenixFramework.getDomainObject(fileEl.getAsString());
                PostFile postFile = new PostFile();
                postFile.setFiles(file);
                postFile.setPost(post);
                postFile.setIndex(idx++);
            }
        }
        return menuItem;
    }

}