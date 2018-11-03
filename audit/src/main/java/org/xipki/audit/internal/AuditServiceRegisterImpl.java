/*
 *
 * Copyright (c) 2013 - 2018 Lijun Liao
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

package org.xipki.audit.internal;

import java.util.concurrent.ConcurrentLinkedDeque;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xipki.audit.AuditService;
import org.xipki.audit.AuditServiceRegister;

/**
 * TODO.
 * @author Lijun Liao
 * @since 2.0.0
 */

public class AuditServiceRegisterImpl implements AuditServiceRegister {

  private static final Logger LOG = LoggerFactory.getLogger(AuditServiceRegisterImpl.class);

  private final transient ConcurrentLinkedDeque<AuditService> services =
      new ConcurrentLinkedDeque<AuditService>();

  private final transient AuditService dfltAuditService = new Slf4jAuditService();

  /**
   * Constructor.
   */
  public AuditServiceRegisterImpl() {
  }

  @Override
  public AuditService getAuditService() {
    return services.isEmpty() ? dfltAuditService : services.getLast();
  }

  public void registService(final AuditService service) {
    //might be null if dependency is optional
    if (service == null) {
      LOG.info("registService invoked with null.");
      return;
    }

    final boolean replaced = services.remove(service);
    services.add(service);

    final String action = replaced ? "replaced" : "added";
    LOG.info("{} AuditService binding for {}", action, service);
  }

  public void unregistService(final AuditService service) {
    //might be null if dependency is optional
    if (service == null) {
      LOG.debug("unregistService invoked with null.");
      return;
    }

    if (services.remove(service)) {
      LOG.info("removed AuditService binding for {}", service);
    } else {
      LOG.info("no AuditService binding found to remove for '{}'", service);
    }
  }

}
