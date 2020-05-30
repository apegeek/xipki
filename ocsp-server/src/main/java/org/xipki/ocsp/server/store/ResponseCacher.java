/*
 *
 * Copyright (c) 2013 - 2020 Lijun Liao
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.xipki.ocsp.server.store;

import java.io.Closeable;
import java.math.BigInteger;
import java.security.cert.CertificateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bouncycastle.crypto.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xipki.datasource.DataAccessException;
import org.xipki.datasource.DataAccessException.Reason;
import org.xipki.datasource.DataSourceWrapper;
import org.xipki.ocsp.api.OcspRespWithCacheInfo;
import org.xipki.ocsp.api.OcspRespWithCacheInfo.ResponseCacheInfo;
import org.xipki.ocsp.api.RequestIssuer;
import org.xipki.security.AlgorithmCode;
import org.xipki.security.HashAlgo;
import org.xipki.security.X509Cert;
import org.xipki.security.util.X509Util;
import org.xipki.util.Args;
import org.xipki.util.Base64;
import org.xipki.util.InvalidConfException;
import org.xipki.util.LogUtil;
import org.xipki.util.StringUtil;
import org.xipki.util.Validity;
import org.xipki.util.concurrent.ConcurrentBag;
import org.xipki.util.concurrent.ConcurrentBagEntry;

/**
 * Response cacher.
 *
 * @author Lijun Liao
 * @since 2.2.0
 */

public class ResponseCacher implements Closeable {

  private static final Logger LOG = LoggerFactory.getLogger(ResponseCacher.class);

  private static final long SEC_PER_WEEK = 7L * 24 * 60 * 60;

  private static final String SQL_ADD_ISSUER = "INSERT INTO ISSUER (ID,S1C,CERT) VALUES (?,?,?)";

  private static final String SQL_SELECT_ISSUER_ID = "SELECT ID FROM ISSUER";

  private static final String SQL_DELETE_ISSUER = "DELETE FROM ISSUER WHERE ID=?";

  private static final String SQL_SELECT_ISSUER = "SELECT ID,CERT FROM ISSUER";

  private static final String SQL_DELETE_EXPIRED_RESP = "DELETE FROM OCSP WHERE THIS_UPDATE<?";

  private static final String SQL_ADD_RESP = "INSERT INTO OCSP (ID,IID,IDENT,"
      + "THIS_UPDATE,NEXT_UPDATE,RESP) VALUES (?,?,?,?,?,?)";

  private static final String SQL_UPDATE_RESP = "UPDATE OCSP SET THIS_UPDATE=?,"
      + "NEXT_UPDATE=?,RESP=? WHERE ID=?";

  private final ConcurrentBag<ConcurrentBagEntry<Digest>> idDigesters;

  private class IssuerUpdater implements Runnable {

    @Override
    public void run() {
      try {
        updateCacheStore();
      } catch (Throwable th) {
        LogUtil.error(LOG, th, "error while calling updateCacheStore()");
      }
    }

  } // class StoreUpdateService

  private class ExpiredResponsesCleaner implements Runnable {

    private final Object lock = new Object();

    private final AtomicBoolean inProcess = new AtomicBoolean(false);

    @Override
    public void run() {
      if (inProcess.get()) {
        return;
      }

      synchronized (lock) {
        inProcess.set(true);
        long maxThisUpdate = System.currentTimeMillis() / 1000 - validity;
        try {
          int num = removeExpiredResponses(maxThisUpdate);
          if (num > 0 && LOG.isInfoEnabled()) {
            Date date = new Date(maxThisUpdate * 1000);
            LOG.info("removed {} with thisUpdate < {} {} ({})",
                num == 1 ? "1 response" : num + " responses", maxThisUpdate, date);
          }
        } catch (Throwable th) {
          LogUtil.error(LOG, th, "could not remove expired responses");
        } finally {
          inProcess.set(false);
        }
      } // end lock
    } // method run

  } // class ExpiredResponsesCleaner

  private final String sqlSelectIssuerCert;

  private final String sqlSelectOcsp;

  private final boolean master;

  // validity in seconds
  private final int validity;

  private final AtomicBoolean onService;

  private DataSourceWrapper datasource;

  private IssuerStore issuerStore = new IssuerStore();

  private ScheduledThreadPoolExecutor scheduledThreadPoolExecutor;

  private ScheduledFuture<?> responseCleaner;

  private ScheduledFuture<?> issuerUpdater;

  public ResponseCacher(DataSourceWrapper datasource, boolean master, Validity validity) {
    this.datasource = Args.notNull(datasource, "datasource");
    this.master = master;
    this.validity = (int) (Args.notNull(validity, "validity").approxMinutes() * 60);
    this.sqlSelectIssuerCert = datasource.buildSelectFirstSql(1, "CERT FROM ISSUER WHERE ID=?");
    this.sqlSelectOcsp = datasource.buildSelectFirstSql(1,
        "IID,IDENT,THIS_UPDATE,NEXT_UPDATE,RESP FROM OCSP WHERE ID=?");
    this.onService = new AtomicBoolean(false);

    this.idDigesters = new ConcurrentBag<>();
    for (int i = 0; i < 20; i++) {
      Digest md = HashAlgo.SHA1.createDigest();
      idDigesters.add(new ConcurrentBagEntry<Digest>(md));
    }
  }

  public boolean isOnService() {
    return onService.get() && issuerStore != null;
  }

  public void init() {
    updateCacheStore();

    scheduledThreadPoolExecutor = new ScheduledThreadPoolExecutor(1);
    scheduledThreadPoolExecutor.setRemoveOnCancelPolicy(true);

    // check every 600 seconds (10 minutes)
    this.responseCleaner = scheduledThreadPoolExecutor.scheduleAtFixedRate(
        new ExpiredResponsesCleaner(), 348, 600, TimeUnit.SECONDS);

    // check every 600 seconds (10 minutes)
    this.issuerUpdater = scheduledThreadPoolExecutor.scheduleAtFixedRate(
        new IssuerUpdater(), 448, 600, TimeUnit.SECONDS);
  } // method init

  @Override
  public void close() {
    if (datasource != null) {
      datasource.close();
      datasource = null;
    }

    if (responseCleaner != null) {
      responseCleaner.cancel(false);
      responseCleaner = null;
    }

    if (issuerUpdater != null) {
      issuerUpdater.cancel(false);
      issuerUpdater = null;
    }

    if (scheduledThreadPoolExecutor != null) {
      scheduledThreadPoolExecutor.shutdown();
      while (!scheduledThreadPoolExecutor.isTerminated()) {
        try {
          Thread.sleep(100);
        } catch (InterruptedException ex) {
          LOG.error("interrupted: {}", ex.getMessage());
        }
      }
      scheduledThreadPoolExecutor = null;
    }
  } // method close

  public Integer getIssuerId(RequestIssuer reqIssuer) {
    IssuerEntry issuer = issuerStore.getIssuerForFp(reqIssuer);
    return (issuer == null) ? null : issuer.getId();
  }

  public synchronized Integer storeIssuer(X509Cert issuerCert)
      throws CertificateException, InvalidConfException, DataAccessException {
    if (!master) {
      throw new IllegalStateException("storeIssuer is not permitted in slave mode");
    }

    for (Integer id : issuerStore.getIds()) {
      if (issuerStore.getIssuerForId(id).getCert().equals(issuerCert)) {
        return id;
      }
    }

    byte[] encodedCert = issuerCert.getEncoded();
    String sha1FpCert = HashAlgo.SHA1.base64Hash(encodedCert);

    int maxId = (int) datasource.getMax(null, "ISSUER", "ID");
    int id = maxId + 1;
    try {
      final String sql = SQL_ADD_ISSUER;
      PreparedStatement ps = null;
      try {
        ps = datasource.prepareStatement(sql);
        int idx = 1;
        ps.setInt(idx++, id);
        ps.setString(idx++, sha1FpCert);
        ps.setString(idx++, Base64.encodeToString(encodedCert));

        ps.execute();

        IssuerEntry newInfo = new IssuerEntry(id, issuerCert);
        issuerStore.addIssuer(newInfo);
        return id;
      } catch (SQLException ex) {
        throw datasource.translate(sql, ex);
      } finally {
        datasource.releaseResources(ps, null);
      }
    } catch (DataAccessException ex) {
      if (ex.getReason().isDescendantOrSelfOf(Reason.DuplicateKey)) {
        return id;
      }
      throw ex;
    }
  } // method storeIssuer

  public OcspRespWithCacheInfo getOcspResponse(int issuerId, BigInteger serialNumber,
      AlgorithmCode sigAlg) throws DataAccessException {
    final String sql = sqlSelectOcsp;
    byte[] identBytes = buildIdent(serialNumber, sigAlg);
    long id = deriveId(issuerId, identBytes);
    PreparedStatement ps = datasource.prepareStatement(sql);
    ResultSet rs = null;

    try {
      ps.setLong(1, id);
      rs = ps.executeQuery();
      if (!rs.next()) {
        return null;
      }

      int dbIid = rs.getInt("IID");
      if (dbIid != issuerId) {
        return null;
      }

      String ident = Base64.encodeToString(identBytes);
      String dbIdent = rs.getString("IDENT");
      if (!ident.equals(dbIdent)) {
        return null;
      }

      long nextUpdate = rs.getLong("NEXT_UPDATE");
      if (nextUpdate != 0) {
        // nextUpdate must be at least in 600 seconds
        long minNextUpdate = System.currentTimeMillis() / 1000 + 600;

        if (nextUpdate < minNextUpdate) {
          return null;
        }
      }

      long thisUpdate = rs.getLong("THIS_UPDATE");
      String b64Resp = rs.getString("RESP");
      byte[] resp = Base64.decodeFast(b64Resp);
      ResponseCacheInfo cacheInfo = new ResponseCacheInfo(thisUpdate);
      if (nextUpdate != 0) {
        cacheInfo.setNextUpdate(nextUpdate);
      }
      return new OcspRespWithCacheInfo(resp, cacheInfo);
    } catch (SQLException ex) {
      throw datasource.translate(sql, ex);
    } finally {
      datasource.releaseResources(ps, rs);
    }
  } // method getOcspResponse

  public void storeOcspResponse(int issuerId, BigInteger serialNumber, long thisUpdate,
      Long nextUpdate, AlgorithmCode sigAlgCode, byte[] response) {
    long nowInSec = System.currentTimeMillis() / 1000;
    if (nextUpdate == null) {
      nextUpdate = nowInSec + SEC_PER_WEEK;
    }

    if (nextUpdate - nowInSec < validity) {
      return;
    }

    byte[] identBytes = buildIdent(serialNumber, sigAlgCode);
    String ident = Base64.encodeToString(identBytes);
    try {
      long id = deriveId(issuerId, identBytes);

      Connection conn = datasource.getConnection();
      try {
        String sql = SQL_ADD_RESP;
        PreparedStatement ps = datasource.prepareStatement(conn, sql);

        String b64Response = Base64.encodeToString(response);
        Boolean dataIntegrityViolationException = null;
        try {
          int idx = 1;
          ps.setLong(idx++, id);
          ps.setInt(idx++, issuerId);
          ps.setString(idx++, ident);
          ps.setLong(idx++, thisUpdate);
          ps.setLong(idx++, nextUpdate);
          ps.setString(idx++, b64Response);
          ps.execute();
        } catch (SQLException ex) {
          DataAccessException dex = datasource.translate(sql, ex);
          if (dex.getReason().isDescendantOrSelfOf(Reason.DataIntegrityViolation)) {
            dataIntegrityViolationException = Boolean.TRUE;
          } else {
            throw dex;
          }
        } finally {
          datasource.releaseResources(ps, null, false);
        }

        if (dataIntegrityViolationException == null) {
          LOG.debug("added cached OCSP response iid={}, ident={}", issuerId, ident);
          return;
        }

        sql = SQL_UPDATE_RESP;
        ps = datasource.prepareStatement(conn, sql);
        try {
          int idx = 1;
          ps.setLong(idx++, thisUpdate);
          ps.setLong(idx++, nextUpdate);
          ps.setString(idx++, b64Response);
          ps.setLong(idx++, id);
          ps.executeUpdate();
        } catch (SQLException ex) {
          throw datasource.translate(sql, ex);
        } finally {
          datasource.releaseResources(ps, null, false);
        }
      } finally {
        datasource.returnConnection(conn);
      }
    } catch (DataAccessException ex) {
      LOG.info("could not cache OCSP response iid={}, ident={}", issuerId, ident);
      if (LOG.isDebugEnabled()) {
        LOG.debug("could not cache OCSP response iid=" + issuerId + ", ident=" + ident, ex);
      }
    }
  } // method storeOcspResponse

  private int removeExpiredResponses(long maxThisUpdate) throws DataAccessException {
    final String sql = SQL_DELETE_EXPIRED_RESP;
    PreparedStatement ps = null;
    try {
      ps = datasource.prepareStatement(sql);
      ps.setLong(1, maxThisUpdate);
      return ps.executeUpdate();
    } catch (SQLException ex) {
      throw datasource.translate(sql, ex);
    } finally {
      datasource.releaseResources(ps, null);
    }
  } // method removeExpiredResponses

  private void updateCacheStore() {
    boolean stillOnService = updateCacheStore0();
    this.onService.set(stillOnService);
    if (!stillOnService) {
      LOG.error("OCSP response cacher is out of service");
    } else {
      LOG.info("OCSP response cacher is on service");
    }
  } // method updateCacheStore

  /**
   * update the cache store.
   * @return whether the ResponseCacher is on service.
   */
  private boolean updateCacheStore0() {
    try {
      if (this.issuerStore == null) {
        return initIssuerStore();
      }

      // check for new issuers
      PreparedStatement ps = null;
      ResultSet rs = null;

      Set<Integer> ids = new HashSet<>();
      try {
        ps = datasource.prepareStatement(SQL_SELECT_ISSUER_ID);
        rs = ps.executeQuery();

        while (rs.next()) {
          ids.add(rs.getInt("ID"));
        }
      } catch (SQLException ex) {
        LogUtil.error(LOG, datasource.translate(SQL_SELECT_ISSUER_ID, ex),
            "could not executing updateCacheStore()");
        return false;
      } catch (Exception ex) {
        LogUtil.error(LOG, ex, "could not executing updateCacheStore()");
        return false;
      } finally {
        datasource.releaseResources(ps, rs, true);
      }

      // add the new issuers
      ps = null;
      rs = null;

      ids.removeAll(issuerStore.getIds());
      if (ids.isEmpty()) {
        // no new issuer
        return true;
      }

      ps = datasource.prepareStatement(sqlSelectIssuerCert);
      try {
        for (Integer id : ids) {
          try {
            ps.setInt(1, id);
            rs = ps.executeQuery();
            rs.next();
            X509Cert cert = X509Util.parseCert(StringUtil.toUtf8Bytes(rs.getString("CERT")));
            IssuerEntry caInfoEntry = new IssuerEntry(id, cert);
            issuerStore.addIssuer(caInfoEntry);
            LOG.info("added issuer {}", id);
          } catch (SQLException ex) {
            LogUtil.error(LOG, datasource.translate(sqlSelectIssuerCert, ex),
                "could not executing updateCacheStore()");
            return false;
          } catch (Exception ex) {
            LogUtil.error(LOG, ex, "could not executing updateCacheStore()");
            return false;
          } finally {
            // only release ResultSet rs here
            datasource.releaseResources(null, rs, false);
          }
        }
      } finally {
        datasource.releaseResources(ps, null, true);
      }
    } catch (DataAccessException ex) {
      LogUtil.error(LOG, ex, "could not executing updateCacheStore()");
      return false;
    } catch (CertificateException ex) {
      // don't set the onService to false.
      LogUtil.error(LOG, ex, "could not executing updateCacheStore()");
    }

    return true;
  } // method updateCacheStore0

  private boolean initIssuerStore() throws DataAccessException, CertificateException {
    PreparedStatement ps = null;
    ResultSet rs = null;

    try {
      ps = datasource.prepareStatement(SQL_SELECT_ISSUER);
      rs = ps.executeQuery();
      List<IssuerEntry> caInfos = new LinkedList<>();

      PreparedStatement deleteIssuerStmt = null;

      while (rs.next()) {
        int id = rs.getInt("ID");
        X509Cert cert = X509Util.parseCert(StringUtil.toUtf8Bytes(rs.getString("CERT")));
        IssuerEntry caInfoEntry = new IssuerEntry(id, cert);
        RequestIssuer reqIssuer = new RequestIssuer(HashAlgo.SHA1,
            caInfoEntry.getEncodedHash(HashAlgo.SHA1));

        boolean duplicated = false;
        for (IssuerEntry existingIssuer : caInfos) {
          if (existingIssuer.matchHash(reqIssuer)) {
            duplicated = true;
            break;
          }
        }

        String subject = cert.getSubject().toString();
        if (duplicated) {
          if (deleteIssuerStmt == null) {
            deleteIssuerStmt = datasource.prepareStatement(SQL_DELETE_ISSUER);
          }

          deleteIssuerStmt.setInt(1, id);
          deleteIssuerStmt.executeUpdate();

          LOG.warn("Delete duplicated issuer {}: {}", id, subject);
        } else {
          LOG.info("added issuer {}: {}", id, subject);
          caInfos.add(caInfoEntry);
        }
      } // end while (rs.next())

      this.issuerStore.setIssuers(caInfos);
      LOG.info("Updated issuers");
    } catch (SQLException ex) {
      throw datasource.translate(SQL_SELECT_ISSUER, ex);
    } finally {
      datasource.releaseResources(ps, rs, false);
    }

    return true;
  } // method initIssuerStore

  private static byte[] buildIdent(BigInteger serialNumber, AlgorithmCode sigAlg) {
    byte[] snBytes = serialNumber.toByteArray();
    byte[] bytes = new byte[1 + snBytes.length];
    bytes[0] = sigAlg.getCode();
    System.arraycopy(snBytes, 0, bytes, 1, snBytes.length);
    return bytes;
  }

  private long deriveId(int issuerId, byte[] identBytes) {
    ConcurrentBagEntry<Digest> digest0 = null;
    try {
      digest0 = idDigesters.borrow(2, TimeUnit.SECONDS);
    } catch (InterruptedException ex) {
      // do nothing
    }

    boolean newDigest = (digest0 == null);
    if (newDigest) {
      digest0 = new ConcurrentBagEntry<Digest>(HashAlgo.SHA1.createDigest());
    }

    byte[] hash = new byte[20];
    try {
      Digest digest = digest0.value();
      digest.reset();
      digest.update(int2Bytes(issuerId), 0, 2);
      digest.update(identBytes, 0, identBytes.length);
      digest.doFinal(hash, 0);
    } finally {
      if (newDigest) {
        idDigesters.add(digest0);
      } else {
        idDigesters.requite(digest0);
      }
    }

    return (0x7FL & hash[0]) << 56 // ignore the first bit
        | (0xFFL & hash[1]) << 48
        | (0xFFL & hash[2]) << 40
        | (0xFFL & hash[3]) << 32
        | (0xFFL & hash[4]) << 24
        | (0xFFL & hash[5]) << 16
        | (0xFFL & hash[6]) << 8
        | (0xFFL & hash[7]);
  } // method deriveId

  private static byte[] int2Bytes(int value) {
    if (value > -1 && value < 65535) {
      return new byte[]{(byte) (value >> 8), (byte) value};
    } else {
      throw new IllegalArgumentException("value is out of the range [0, 65535]: " + value);
    }
  }

}
