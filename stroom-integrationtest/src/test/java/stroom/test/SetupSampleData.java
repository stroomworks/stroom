/*
 * Copyright 2016 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.test;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import stroom.logging.spring.EventLoggingConfiguration;
import stroom.dashboard.spring.DashboardConfiguration;
import stroom.index.spring.IndexConfiguration;
import stroom.script.spring.ScriptConfiguration;
import stroom.search.spring.SearchConfiguration;
import stroom.security.spring.SecurityConfiguration;
import stroom.visualisation.spring.VisualisationConfiguration;
import stroom.CommonTestControl;
import stroom.pipeline.spring.PipelineConfiguration;
import stroom.spring.CachedServiceConfiguration;
import stroom.spring.PersistenceConfiguration;
import stroom.spring.ScopeConfiguration;
import stroom.spring.ScopeTestConfiguration;
import stroom.spring.ServerConfiguration;
import stroom.util.io.FileUtil;
import stroom.util.spring.StroomSpringProfiles;
import stroom.util.task.TaskScopeContextHolder;
import stroom.util.thread.ThreadScopeContextHolder;

/**
 * Script to create some base data for testing.
 */
public final class SetupSampleData {
    public static void main(final String[] args) throws Exception {
        FileUtil.useDevTempDir();
        System.setProperty("stroom.connectionTesterClassName",
                "stroom.entity.server.util.StroomConnectionTesterOkOnException");

        ThreadScopeContextHolder.createContext();
        try {
            TaskScopeContextHolder.addContext();
            try {
                @SuppressWarnings("resource")
                final AnnotationConfigApplicationContext appContext = new AnnotationConfigApplicationContext();
                appContext.getEnvironment().setActiveProfiles(StroomSpringProfiles.PROD,
                        SecurityConfiguration.MOCK_SECURITY);
                appContext.register(ScopeConfiguration.class, PersistenceConfiguration.class,
                        SetupSampleDataComponentScanConfiguration.class, ServerConfiguration.class,
                        CachedServiceConfiguration.class, SecurityConfiguration.class, ScopeTestConfiguration.class,
                        PipelineConfiguration.class, EventLoggingConfiguration.class, IndexConfiguration.class,
                        SearchConfiguration.class, ScriptConfiguration.class, VisualisationConfiguration.class,
                        DashboardConfiguration.class);
                appContext.refresh();
                final CommonTestControl commonTestControl = appContext.getBean(CommonTestControl.class);

                commonTestControl.setup();

                final SetupSampleDataBean setupSampleDataBean = appContext.getBean(SetupSampleDataBean.class);
                setupSampleDataBean.run(true);

            } finally {
                TaskScopeContextHolder.removeContext();
            }
        } finally {
            ThreadScopeContextHolder.destroyContext();
        }
    }
}
