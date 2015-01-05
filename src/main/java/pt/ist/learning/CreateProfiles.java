package pt.ist.learning;

import org.fenixedu.academic.domain.Person;
import org.fenixedu.academic.domain.organizationalStructure.Party;
import org.fenixedu.bennu.core.domain.Bennu;
import org.fenixedu.bennu.core.domain.UserProfile;
import org.fenixedu.bennu.scheduler.custom.CustomTask;

public class CreateProfiles extends CustomTask {

    @Override
    public void runTask() throws Exception {
        for (Party party : Bennu.getInstance().getPartysSet()) {
            if (party instanceof Person) {
                Person person = (Person) party;
                if (person.getProfile() == null) {
                    person.setProfile(new UserProfile("Utilizador de", "Teste", "Utilizador de Teste", "test@user.pt", null));
                    taskLog("Created profile for %s\n", person);
                }
            }
        }
    }

}
