package muon.app.vps;

import java.time.LocalDate;
import java.util.Objects;

public final class VpsBillingDates {

    private VpsBillingDates() {
    }

    public static String nextMonthlyDate() {
        return LocalDate.now().plusMonths(1).toString();
    }

    public static String nextFixedDate(String billingCycle, int billingPeriodDays) {
        if ("yearly".equalsIgnoreCase(Objects.toString(billingCycle, ""))) {
            return LocalDate.now().plusYears(1).toString();
        }
        if ("custom".equalsIgnoreCase(Objects.toString(billingCycle, ""))) {
            return LocalDate.now().plusDays(Math.max(1, billingPeriodDays)).toString();
        }
        return nextMonthlyDate();
    }
}
