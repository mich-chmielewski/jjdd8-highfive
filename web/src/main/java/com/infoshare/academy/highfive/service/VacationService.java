package com.infoshare.academy.highfive.service;

import com.infoshare.academy.highfive.dao.*;
import com.infoshare.academy.highfive.domain.*;
import com.infoshare.academy.highfive.dto.request.VacationRequest;
import com.infoshare.academy.highfive.dto.view.VacationMonthView;
import com.infoshare.academy.highfive.dto.view.VacationStatisticView;
import com.infoshare.academy.highfive.dto.view.VacationView;
import com.infoshare.academy.highfive.mapper.entity.VacationEmployeeMapper;
import com.infoshare.academy.highfive.mapper.entity.VacationMapper;
import com.infoshare.academy.highfive.service.configuration.MailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.transaction.Transactional;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequestScoped
public class VacationService {

  @Inject
  EmployeeDao employeeDao;

  @Inject
  HolidayDao holidayDao;

  @Inject
  VacationDao vacationDao;

  @Inject
  VacationMapper vacationMapper;

  @Inject
  EntitlementDao entitlementDao;

  @Inject
  MailSender mailSender;

  @Inject
  StatisticDao statisticDao;

  @Inject
  VacationEmployeeMapper vacationEmployeeMapper;

  Logger LOGGER = LoggerFactory.getLogger(getClass().getName());

  private String status;

  public String getStatus() {
    return status;
  }

  public void addVacation(VacationRequest vacationRequest) {

    Employee employee = employeeDao.getEmployeeById(vacationRequest.getEmployeeId());
    Entitlement entitlement = entitlementDao.getEntitlementByEmployeeId(employee);

    List<LocalDate> vacationDaysList = creatingVacationDaysList(vacationRequest);
    int entitledDays = getEmployeeEntitlement(entitlement, vacationRequest);
    int daysOff = calculatingDaysAmount(vacationDaysList);


    if (vacationRequest.getDateTo().isBefore((vacationRequest.getDateFrom()))) {

      status = "wrong_date";
    } else {

      if (checkIfVacationIsAvailable(daysOff, entitledDays)) {

        vacationDao.addVacation(vacationMapper.mapRequestToEntity(employee, vacationRequest));
        updateHolidayEntitlement(vacationRequest, daysOff, entitlement);

        vacationDaysList.forEach(day -> {
          Statistic statisticMonth = statisticDao.getStatisticByMonth(day.getMonthValue());
          long vacationDaysCount = statisticMonth.getVacationDaysCount();
          statisticMonth.setVacationDaysCount(vacationDaysCount + 1);
          statisticDao.saveStatistic(statisticMonth);
        });

        LOGGER.info("Vacation added successfully");
        status = "ok";
      } else {
        status = "exceeding_entitlement";

        LOGGER.warn("Given days are exceeding entitledDays.");
      }
    }
  }

  void updateHolidayEntitlement(VacationRequest vacationRequest, int daysOff, Entitlement entitlement) {
    entitlement.setVacationTaken(entitlement.getVacationTaken() + daysOff);

    if (vacationRequest.getVacationType().equals(VacationType.PARENTAL)) {

      entitlement.setAdditionalLeft(entitlement.getAdditionalLeft() - daysOff);

    } else if (vacationRequest.getVacationType().equals(VacationType.ON_DEMAND)) {

      entitlement.setOnDemandVacationLeft(entitlement.getOnDemandVacationLeft() - daysOff);

    } else {

      entitlement.setVacationLeft(entitlement.getVacationLeft() - daysOff);

    }
    entitlementDao.updateEntitlement(entitlement);
  }

  int getEmployeeEntitlement(Entitlement entitlement, VacationRequest vacationRequest) {

    if (vacationRequest.getVacationType().equals(VacationType.PARENTAL)) {

      return entitlement.getAdditionalLeft();

    } else if (vacationRequest.getVacationType().equals(VacationType.ON_DEMAND)) {

      return entitlement.getOnDemandVacationLeft();

    } else {

      return entitlement.getVacationLeft() + entitlement.getPreviousYearLeft();

    }

  }

  boolean checkIfVacationIsAvailable(int daysOff, int entitlement) {

    return daysOff <= entitlement;

  }

  List<LocalDate> creatingVacationDaysList(VacationRequest vacationRequest) {

    LocalDate start = vacationRequest.getDateFrom();
    LocalDate end = vacationRequest.getDateTo();
    List<LocalDate> totalDates = new LinkedList<>();
    while (!start.isAfter(end)) {
      totalDates.add(start);
      start = start.plusDays(1);
    }

    return totalDates;

  }

  Integer calculatingDaysAmount(List<LocalDate> totalDates) {

    List<LocalDate> holidays = new LinkedList<>();
    List<LocalDate> publicHolidayList = holidayDao.listAllHolidayDates();

    for (LocalDate day : totalDates) {
      if ("SATURDAY".equals(day.getDayOfWeek().name())) {

        holidays.add(day);

      } else if ("SUNDAY".equals(day.getDayOfWeek().name())) {

        holidays.add(day);

      } else if (publicHolidayList.contains(day)) {

        holidays.add(day);

      }

    }

    return totalDates.size() - holidays.size();

  }

  @Transactional
  public List<VacationView> listAllPendingRequests() {

    return vacationDao.getVacationList(VacationStatus.APPLIED)
      .stream()
      .map(vacation -> vacationMapper.mapEntityToView(vacation))
      .collect(Collectors.toList());

  }

  public void changeVacationStatus(Long vacationId, VacationStatus vacationStatus) throws IOException {

    Vacation vacation = vacationDao.getVacationById(vacationId);
    vacation.setVacationStatus(vacationStatus);
    vacationDao.updateVacationStatus(vacation);

    if (vacationStatus.equals(VacationStatus.APPROVED)) {

//      mailSender.sendApprove(vacation.getEmployee().getEmail());

    } else {
//      mailSender.sendReject(vacation.getEmployee().getEmail());
    }

  }

  public VacationStatisticView getDashboardStatistic() {
    VacationStatisticView vacationStatisticView = new VacationStatisticView();
    vacationStatisticView.setNextMonthTotal(vacationDao.getAmountOfAbsentNextMonth());
    vacationStatisticView.setCurrentMonthTotal(vacationDao.getAmountOfAbsentThisMonth());
    vacationStatisticView.setPendingRequests(vacationDao.getVacationList(VacationStatus.APPLIED).size());
    vacationStatisticView.setAbsentToday(vacationDao.getAmountOfAbsentToday());
    return vacationStatisticView;
  }

  public Double getApprovedToDeniedVacationRatio() {
    double approvedVacation = vacationDao.getVacationList(VacationStatus.APPROVED).size();
    double deniedVacation = vacationDao.getVacationList(VacationStatus.DENIED).size();
    return approvedVacation / (deniedVacation + approvedVacation) * 100;
  }

  public List<Entitlement> getEmployeesByVacationTaken() {
    return entitlementDao.getVacationTakenByEmployee();
  }

  public Map<Object, List<Entitlement>> getTeamByVacationTaken() {
    List<Entitlement> entitlementList = entitlementDao.getVacationTakenByTeam();
//    return entitlementList.stream()
//      .collect(Collectors.groupingBy(entitlement -> entitlement.getEmployee().getTeam().getTeamName()), Collectors.counting());
    return null;
  }


  public List<VacationMonthView> getMonthStatistic() {
    List<VacationMonthView> vacationMonthView = new ArrayList<>();
    statisticDao.getStatisticListSortedByVacationDaysCount().
      forEach(h -> vacationMonthView.add(vacationEmployeeMapper.mapEntityToView(h)));

    return vacationMonthView;
  }
}

