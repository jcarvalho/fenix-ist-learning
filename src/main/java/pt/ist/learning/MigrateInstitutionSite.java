package pt.ist.learning;

import java.lang.reflect.Constructor;

import org.fenixedu.bennu.core.domain.Bennu;
import org.fenixedu.bennu.core.groups.AnyoneGroup;
import org.fenixedu.bennu.core.security.Authenticate;
import org.fenixedu.bennu.scheduler.custom.CustomTask;
import org.fenixedu.cms.domain.PersistentSiteViewersGroup;
import org.fenixedu.cms.domain.Site;
import org.fenixedu.commons.StringNormalizer;

import pt.ist.fenix.domain.unit.UnitSite;

public class MigrateInstitutionSite extends CustomTask {

    @Override
    public void runTask() throws Exception {
        UnitSite site = Bennu.getInstance().getInstitutionUnit().getSite();
        taskLog("Site: %s\n", site);

        Bennu.getInstance().setDefaultSite(site);

        site.setSlug("tecnicolisboa");

        site.setCanViewGroup(AnyoneGroup.get());

        try {
            Constructor<?> ctor = PersistentSiteViewersGroup.class.getDeclaredConstructor(Site.class);
            ctor.setAccessible(true);
            ctor.newInstance(site);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        site.setBennu(Bennu.getInstance());
        site.setCreatedBy(Authenticate.getUser());

        site.updateMenuFunctionality();

        site.getPostSet().forEach(post -> {
            post.setSlug(StringNormalizer.slugify(post.getName().getContent()));
            if (post.getCreatedBy() == null) {
                taskLog("Post %s has no creator!\n", post);
                post.setCreatedBy(Authenticate.getUser());
            }
        });
        site.getCategoriesSet().forEach(cat -> {
            cat.setSlug(StringNormalizer.slugify(cat.getName().getContent()));
            if (cat.getCreatedBy() == null) {
                taskLog("Category %s has no creator!\n", cat);
                cat.setCreatedBy(Authenticate.getUser());
            }
        });
    }

}
