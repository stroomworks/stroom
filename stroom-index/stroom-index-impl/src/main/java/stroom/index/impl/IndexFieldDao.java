/*
 * Copyright 2018-2024 Crown Copyright
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

package stroom.index.impl;


import stroom.docref.DocRef;
import stroom.index.shared.AddField;
import stroom.index.shared.DeleteField;
import stroom.index.shared.UpdateField;
import stroom.query.api.datasource.FindFieldCriteria;
import stroom.query.api.datasource.IndexField;
import stroom.util.shared.ResultPage;

import java.util.Collection;

public interface IndexFieldDao {

    void addFields(DocRef docRef, Collection<IndexField> fields);

    ResultPage<IndexField> findFields(FindFieldCriteria criteria);

    int getFieldCount(DocRef docRef);

    void addField(AddField addField);

    void updateField(UpdateField updateField);

    void deleteField(DeleteField deleteField);

    void deleteAll(DocRef docRef);

    void copyAll(DocRef source, DocRef dest);
}
