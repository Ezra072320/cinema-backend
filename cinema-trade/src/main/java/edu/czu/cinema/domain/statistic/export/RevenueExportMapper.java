package edu.czu.cinema.domain.statistic.export;

import edu.czu.cinema.domain.statistic.dto.RevenueReport;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel="spring", unmappedTargetPolicy=ReportingPolicy.ERROR)
public interface RevenueExportMapper {
    @Mapping(target=".", source="metrics")
    RevenueExportRows.Period period(RevenueReport.PeriodRow row);
    RevenueExportRows.Rank rank(RevenueReport.RankRow row);
}
