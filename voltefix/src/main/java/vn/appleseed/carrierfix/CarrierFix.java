package vn.appleseed.carrierfix;

import android.os.IBinder;
import android.os.PersistableBundle;
import android.os.ServiceManager;
import java.lang.reflect.Method;

public final class CarrierFix {
  static final String[] KEYS = {
    "hide_enhanced_4g_lte_bool",
    "editable_enhanced_4g_lte_bool",
    "enhanced_4g_lte_on_by_default_bool",
    "carrier_volte_available_bool",
    "carrier_volte_provisioned_bool",
    "carrier_vt_available_bool",
    "carrier_wfc_ims_available_bool",
    "carrier_supports_ss_over_ut_bool",
    "show_4g_for_lte_data_icon_bool"
  };

  public static void main(String[] args) throws Exception {
    int subId = 1;
    if (args.length > 0) try { subId = Integer.parseInt(args[0]); } catch (Throwable ignored) {}
    IBinder b = ServiceManager.getService("carrier_config");
    if (b == null) throw new IllegalStateException("carrier_config binder unavailable");
    Class<?> stub = Class.forName("com.android.internal.telephony.ICarrierConfigLoader$Stub");
    Method asInterface = stub.getMethod("asInterface", IBinder.class);
    Object loader = asInterface.invoke(null, b);
    PersistableBundle p = new PersistableBundle();
    p.putBoolean("hide_enhanced_4g_lte_bool", false);
    p.putBoolean("editable_enhanced_4g_lte_bool", true);
    p.putBoolean("enhanced_4g_lte_on_by_default_bool", true);
    p.putBoolean("carrier_volte_available_bool", true);
    p.putBoolean("carrier_volte_provisioned_bool", true);
    p.putBoolean("carrier_vt_available_bool", true);
    p.putBoolean("carrier_wfc_ims_available_bool", true);
    p.putBoolean("carrier_supports_ss_over_ut_bool", true);
    p.putBoolean("show_4g_for_lte_data_icon_bool", true);

    Method override = null, notify = null;
    for (Method m : loader.getClass().getMethods()) {
      if (m.getName().equals("overrideConfig")) override = m;
      if (m.getName().equals("notifyConfigChangedForSubId")) notify = m;
    }
    if (override == null) throw new NoSuchMethodException("overrideConfig");
    if (override.getParameterTypes().length == 3) {
      override.invoke(loader, subId, p, true);
    } else {
      override.invoke(loader, subId, p);
    }
    if (notify != null) notify.invoke(loader, subId);
    System.out.println("APPLESEED_CARRIERCONFIG_SUCCESS");
  }
}
