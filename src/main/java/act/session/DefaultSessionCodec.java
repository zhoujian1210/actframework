package act.session;

/*-
 * #%L
 * ACT Framework
 * %%
 * Copyright (C) 2014 - 2017 ActFramework
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import act.app.App;
import act.conf.AppConfig;
import act.util.DestroyableBase;
import org.osgl.$;
import org.osgl.http.H;
import org.osgl.util.C;
import org.osgl.util.Charsets;
import org.osgl.util.Codec;
import org.osgl.util.S;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.osgl.http.H.Session.KEY_EXPIRATION;
import static org.osgl.http.H.Session.KEY_EXPIRE_INDICATOR;

@Singleton
public class DefaultSessionCodec extends DestroyableBase implements SessionCodec {

    private final boolean sessionWillExpire;
    private final boolean encryptSession;
    private final int ttl;
    private final String pingPath;
    private App app;

    @Inject
    public DefaultSessionCodec(AppConfig conf) {
        app = conf.app();
        ttl = conf.sessionTtl() * 1000;
        sessionWillExpire = ttl > 0;
        pingPath = conf .pingPath();
        encryptSession = conf.encryptSession();
    }

    @Override
    protected void releaseResources() {
        app = null;
    }

    @Override
    public String encodeSession(H.Session session) {
        if (null == session) {
            return null;
        }
        boolean sessionChanged = session.changed();
        if (!sessionChanged && (session.empty() || !sessionWillExpire)) {
            // Nothing changed and no cookie-expire or empty, consequently send nothing back.
            return null;
        }
        session.id(); // ensure session ID is generated
        if (sessionWillExpire && !session.contains(KEY_EXPIRATION)) {
            // session get cleared before
            session.put(KEY_EXPIRATION, $.ms() + ttl);
        }
        return dissolveIntoCookieContent(session, true);
    }

    @Override
    public String encodeFlash(H.Flash flash) {
        if (null == flash || flash.isEmpty()) {
            return null;
        }
        return dissolveIntoCookieContent(flash.out(), false);
    }

    @Override
    public H.Session decodeSession(String encodedSession, H.Request request) {
        H.Session session = new H.Session();
        boolean newSession = true;
        if (S.notBlank(encodedSession)) {
            resolveFromCookieContent(session, encodedSession, true);
            newSession = false;
        }
        session = processExpiration(session, $.ms(), newSession, sessionWillExpire, ttl, pingPath, request);
        return session;
    }

    @Override
    public H.Flash decodeFlash(String encodedFlash) {
        H.Flash flash = new H.Flash();
        if (S.notBlank(encodedFlash)) {
            resolveFromCookieContent(flash, encodedFlash, false);
            flash.discard(); // prevent cookie content from been output to response again
        }
        return flash;
    }

    private void resolveFromCookieContent(H.KV<?> kv, String content, boolean isSession) {
        String data = Codec.decodeUrl(content, Charsets.UTF_8);
        if (isSession) {
            if (encryptSession) {
                try {
                    data = app.decrypt(data);
                } catch (Exception e) {
                    return;
                }
            }
            int firstDashIndex = data.indexOf("-");
            if (firstDashIndex < 0) {
                return;
            }
            String sign = data.substring(0, firstDashIndex);
            data = data.substring(firstDashIndex + 1);
            String sign1 = app.sign(data);
            if (!sign.equals(sign1)) {
                return;
            }
        }
        List<char[]> pairs = split(data.toCharArray(), '\u0000');
        if (pairs.isEmpty()) return;
        for (char[] pair: pairs) {
            List<char[]> kAndV = split(pair, '\u0001');
            int sz = kAndV.size();
            if (sz != 2) {
                S.Buffer sb = S.newBuffer();
                for (int i = 0; i < sz; ++i) {
                    if (i > 0) sb.append(":");
                    sb.append(Arrays.toString(kAndV.get(i)));
                }
                warn("unexpected KV string: %S", sb.toString());
            } else {
                kv.put(new String(kAndV.get(0)), new String(kAndV.get(1)));
            }
        }
    }

    private List<char[]> split(char[] content, char separator) {
        int len = content.length;
        if (0 == len) {
            return C.list();
        }
        List<char[]> l = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < len; ++i) {
            char c = content[i];
            if (c == separator) {
                if (i == start) {
                    start++;
                    continue;
                }
                char[] ca = new char[i - start];
                System.arraycopy(content, start, ca, 0, i - start);
                l.add(ca);
                start = i + 1;
            }
        }
        if (start == 0) {
            l.add(content);
        } else {
            char[] ca = new char[len - start];
            System.arraycopy(content, start, ca, 0, len - start);
            l.add(ca);
        }
        return l;
    }

    private String dissolveIntoCookieContent(H.KV<?> kv, boolean isSession) {
        S.Buffer sb = S.buffer();
        int i = 0;
        for (String k : kv.keySet()) {
            if (i > 0) {
                sb.append("\u0000");
            }
            sb.append(k);
            sb.append("\u0001");
            sb.append(kv.get(k));
            i++;
        }
        String data = sb.toString();
        if (isSession) {
            String sign = app.sign(data);
            data = S.concat(sign, "-", data);
            if (encryptSession) {
                data = app.encrypt(data);
            }
        }
        data = Codec.encodeUrl(data, Charsets.UTF_8);
        return data;
    }

    static H.Session processExpiration(H.Session session, long now, boolean newSession, boolean sessionWillExpire, int ttl, String pingPath, H.Request request) {
        if (!sessionWillExpire) return session;
        long expiration = now + ttl;
        if (newSession) {
            // no previous cookie to restore; but we need to set the timestamp in the new cookie
            // note we use `load` API instead of `put` because we don't want to set the dirty flag
            // in this case
            session.load(KEY_EXPIRATION, String.valueOf(expiration));
        } else {
            String s = session.get(KEY_EXPIRATION);
            long oldTimestamp = null == s ? -1 : Long.parseLong(s);
            long newTimestamp = expiration;
            // Verify that the session contains a timestamp, and that it's not expired
            if (oldTimestamp < 0) {
                // invalid session, reset it
                session = new H.Session();
            } else {
                if (oldTimestamp < now) {
                    // Session expired
                    session = new H.Session();
                    session.put(KEY_EXPIRE_INDICATOR, true);
                } else {
                    session.remove(KEY_EXPIRE_INDICATOR);
                    boolean skipUpdateExpiration = S.eq(pingPath, request.url());
                    if (skipUpdateExpiration) {
                        newTimestamp = oldTimestamp;
                    }
                }
            }
            session.put(KEY_EXPIRATION, newTimestamp);
        }
        return session;
    }

}
