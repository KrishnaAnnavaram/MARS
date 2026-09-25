package com.aura.vihanga.employeeservice.service.implementation;

import com.aura.vihanga.employeeservice.model.Employee;
import com.aura.vihanga.employeeservice.utill.EmployeeType;
import com.aura.vihanga.employeeservice.utill.GenderType;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Slf4j
public class ExcelUploadImpl {

    public static boolean isValidExcelFile(MultipartFile multipartFile) {
        String expectedContentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        return Objects.equals(multipartFile.getContentType(), expectedContentType);
    }

    public static List<Employee> getEmployeeDataFromExcel(MultipartFile multipartFile) throws IOException {
        List<Employee> employees = new ArrayList<>();

        Workbook workbook = new XSSFWorkbook(multipartFile.getInputStream());
        Sheet sheet = workbook.getSheetAt(0);


        for(int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row currentRow = sheet.getRow(i);



            Employee employee = new Employee();
            employee.setEmployeeId(currentRow.getCell(0).getStringCellValue());
            employee.setName(currentRow.getCell(1).getStringCellValue());
            employee.setDepartment(currentRow.getCell(2).getStringCellValue());
            Cell phoneNoCell = currentRow.getCell(3);
            if (phoneNoCell.getCellType() == CellType.STRING) {
                employee.setPhoneNo(phoneNoCell.getStringCellValue());
            } else if (phoneNoCell.getCellType() == CellType.NUMERIC) {
                employee.setPhoneNo(String.valueOf(phoneNoCell.getNumericCellValue()));
            } else {
                employee.setPhoneNo("");
            }
            employee.setAddress(currentRow.getCell(4).getStringCellValue());
            employee.setGender(GenderType.valueOf(currentRow.getCell(5).getStringCellValue()));
            employee.setEmployeeType(EmployeeType.valueOf(currentRow.getCell(6).getStringCellValue()));



            employees.add(employee);

        }

        workbook.close();

        return employees;
    }
}
