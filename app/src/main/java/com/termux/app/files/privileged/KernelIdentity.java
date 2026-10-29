package com.termux.app.files.privileged;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

final class KernelIdentity {
    private KernelIdentity() { }

    static int effectiveUid(Reader status) throws IOException {
        BufferedReader lines = new BufferedReader(status);
        String line;
        int count = 0;
        while ((line = lines.readLine()) != null && ++count <= 512) {
            if (!line.startsWith("Uid:")) continue;
            String[] values = line.substring(4).trim().split("\\s+");
            if (values.length != 4) throw new IOException("Invalid kernel UID record");
            try {
                int real = Integer.parseInt(values[0]);
                int effective = Integer.parseInt(values[1]);
                int saved = Integer.parseInt(values[2]);
                int filesystem = Integer.parseInt(values[3]);
                if (real < 0 || real != effective || saved != effective || filesystem != effective)
                    throw new IOException("Inconsistent kernel UID credentials");
                return effective;
            } catch (NumberFormatException e) { throw new IOException("Invalid kernel UID", e); }
        }
        throw new IOException("Kernel UID record unavailable; privilege cannot be verified");
    }

    static void requirePrivilege(int uid, boolean root) throws IOException {
        if (root ? uid != 0 : uid != 0 && uid != 2000)
            throw new IOException(root ? "Real ROOT (UID 0) was not granted" : "Shizuku did not grant shell or root identity");
    }
}
