package stroom.data.retention.shared;

import stroom.entity.shared.ExpressionCriteria;
import stroom.query.api.ExpressionOperator;
import stroom.util.shared.CriteriaFieldSort;
import stroom.util.shared.PageRequest;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class FindDataRetentionImpactCriteria extends ExpressionCriteria {

    public FindDataRetentionImpactCriteria() {
        super();
    }

    @JsonCreator
    public FindDataRetentionImpactCriteria(@JsonProperty("pageRequest") final PageRequest pageRequest,
                                           @JsonProperty("sortList") final List<CriteriaFieldSort> sortList,
                                           @JsonProperty("expression") final ExpressionOperator expression) {
        super(pageRequest, sortList, expression);
    }
}
